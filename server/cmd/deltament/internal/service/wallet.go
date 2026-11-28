package service

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"strconv"
	"time"

	"telesrv/cmd/deltament/internal/domain"
	"telesrv/cmd/deltament/internal/network"
	"telesrv/cmd/deltament/internal/secret"
	"telesrv/cmd/deltament/internal/store"
)

type walletStore interface {
	GetServer(ctx context.Context, id int64) (domain.Server, error)
	store.Ledger
	store.Items
	store.TON
	store.TxRunner
}

// баланс, пополнение звёздами с приватки, продажа и вывод предметов
type Wallet struct {
	db   walletStore
	nets Networks
	log  *slog.Logger
}

func NewWallet(db walletStore, nets Networks, log *slog.Logger) *Wallet {
	return &Wallet{db: db, nets: nets, log: log}
}

func (w *Wallet) History(ctx context.Context, userID int64) ([]domain.HistoryEntry, error) {
	return w.db.History(ctx, userID, 200)
}

func (w *Wallet) Items(ctx context.Context, userID int64) ([]domain.Item, error) {
	return w.db.ListItems(ctx, userID)
}

// списываем звёзды на приватке и начисляем GRAM по курсу сервера
func (w *Wallet) TopUp(ctx context.Context, u domain.User, gram int64) (domain.Cents, error) {
	if gram < 1 || gram > 1_000_000 {
		return 0, fmt.Errorf("%w: amount must be 1-1000000", domain.ErrInvalid)
	}
	srv, err := w.db.GetServer(ctx, u.ServerID)
	if err != nil {
		return 0, err
	}
	if srv.StarsPerGram <= 0 {
		return 0, fmt.Errorf("%w: top up is disabled on this server", domain.ErrForbidden)
	}
	net, err := w.nets.Get(ctx, u.ServerID)
	if err != nil {
		return 0, err
	}
	stars := int64(math.Ceil(float64(gram) * srv.StarsPerGram))
	op := network.Op{Key: "topup:" + strconv.FormatInt(u.ID, 10) + ":" + secret.Hex(8), Reason: "Deltament top up"}
	if err := net.DebitStars(ctx, op, u.ServerUserID, stars); err != nil {
		return 0, err
	}
	amount := domain.Cents(gram * 100)
	title := fmt.Sprintf("Пополнение · %d ⭐", stars)
	if err := w.db.Credit(ctx, u.ID, amount, domain.HistoryTopup, title); err != nil {
		// звёзды уже списаны, это надо разбирать руками
		w.log.Error("topup credit failed after debit", "user", u.ID, "stars", stars, "op", op.Key, "err", err)
		return 0, err
	}
	return amount, nil
}

// Sell lists an owned item for resale. The marketplace does NOT pay the owner
// here: the previous version credited item.Value immediately and relisted the
// lot, which let two accounts mint unlimited GRAM by buying and "selling" the
// same username in a loop. Now the item is consigned (the underlying asset is
// already in the operator vault) and the owner is paid only when a real buyer
// purchases the relisted lot.
func (w *Wallet) Sell(ctx context.Context, u domain.User, itemID int64) (domain.Cents, error) {
	var price domain.Cents
	err := w.db.InTx(ctx, func(tx store.Tx) error {
		it, err := tx.LockItem(ctx, u.ID, itemID)
		if err != nil {
			return err
		}
		if it.Status != domain.ItemOwned {
			return fmt.Errorf("%w: item is %s", domain.ErrConflict, it.Status)
		}
		if it.GiftRowID > 0 {
			// A real gift is already escrowed on the relayer; selling it back
			// would relist the lot without the asset. The buyer can only move
			// it to their profile via /api/items/{id}/claim.
			return fmt.Errorf("%w: use \"Добавить в профиль\" for marketplace gifts", domain.ErrInvalid)
		}
		if it.Value < 100 || it.Value > maxLotCents {
			return fmt.Errorf("%w: item value is outside the allowed lot price", domain.ErrInvalid)
		}
		l, err := tx.LockLot(ctx, it.LotID)
		if err != nil {
			return err
		}
		if l.Status != domain.LotSold {
			return fmt.Errorf("%w: lot is %s", domain.ErrConflict, l.Status)
		}
		// Consign the item back to the marketplace. Escrowed=true records that
		// no seller asset has to be moved at the next settlement: gifts are
		// rejected above, user assets already sit in the vault, and operator
		// lots never had an owned asset to begin with.
		l.Status, l.Price, l.EndsAt = domain.LotSale, it.Value, time.Time{}
		l.SellerUserID = u.ID
		l.Escrowed = true
		if err := tx.UpdateLot(ctx, l); err != nil {
			return err
		}
		if err := tx.SetItemStatus(ctx, it.ID, domain.ItemSold); err != nil {
			return err
		}
		price = it.Value
		return nil
	})
	return price, err
}

// WithdrawStars converts marketplace GRAM back into DeltaGram stars at the
// server rate. One GRAM is stars_per_gram stars (100 by default).
func (w *Wallet) WithdrawStars(ctx context.Context, u domain.User, amount domain.Cents) (int64, error) {
	if amount < 100 {
		return 0, fmt.Errorf("%w: minimum withdrawal is 1 GRAM", domain.ErrInvalid)
	}
	srv, err := w.db.GetServer(ctx, u.ServerID)
	if err != nil {
		return 0, err
	}
	if srv.StarsPerGram <= 0 {
		return 0, fmt.Errorf("%w: withdrawal is disabled on this server", domain.ErrForbidden)
	}
	stars := int64(math.Round(float64(amount) / 100 * srv.StarsPerGram))
	if stars < 1 {
		return 0, fmt.Errorf("%w: amount is too small", domain.ErrInvalid)
	}
	net, err := w.nets.Get(ctx, u.ServerID)
	if err != nil {
		return 0, err
	}
	title := fmt.Sprintf("Вывод · %d ⭐", stars)
	if err := w.db.InTx(ctx, func(tx store.Tx) error {
		return tx.Debit(ctx, u.ID, amount, domain.HistoryWithdraw, title)
	}); err != nil {
		return 0, err
	}
	op := network.Op{Key: "withdraw:" + strconv.FormatInt(u.ID, 10) + ":" + secret.Hex(8), Reason: "Deltament stars withdrawal"}
	if err := net.GrantStars(ctx, op, u.ServerUserID, stars); err != nil {
		// звёзды не дошли, возвращаем GRAM на баланс
		if rerr := w.db.Credit(ctx, u.ID, amount, domain.HistoryRefund, "Возврат вывода"); rerr != nil {
			w.log.Error("withdraw refund failed", "user", u.ID, "amount", amount, "err", rerr)
		}
		return 0, err
	}
	// The gold card mirrors earned stars; a failed bookkeeping write must not
	// undo a grant that already reached the DeltaGram account.
	if err := w.db.CreditStars(ctx, u.ServerUserID, stars, "withdraw_stars", "Зачисление звёзд на карту"); err != nil {
		w.log.Error("star card credit failed", "user", u.ID, "stars", stars, "err", err)
	}
	return stars, nil
}

// вывод на аккаунт в приватке, ключ идемпотентности не даст выдать дважды
func (w *Wallet) Withdraw(ctx context.Context, u domain.User, itemID int64) error {
	var it domain.Item
	err := w.db.InTx(ctx, func(tx store.Tx) error {
		var err error
		if it, err = tx.LockItem(ctx, u.ID, itemID); err != nil {
			return err
		}
		if it.Status != domain.ItemOwned {
			return fmt.Errorf("%w: item is %s", domain.ErrConflict, it.Status)
		}
		if it.GiftRowID > 0 {
			// The gift object already exists on the relayer account. Minting a
			// new one through the network would duplicate it.
			return fmt.Errorf("%w: use \"Добавить в профиль\" for marketplace gifts", domain.ErrInvalid)
		}
		return tx.SetItemStatus(ctx, it.ID, domain.ItemWithdrawn)
	})
	if err != nil {
		return err
	}
	if err := w.deliver(ctx, u, it); err != nil {
		// не вышло, возвращаем предмет в инвентарь
		if rerr := w.db.SetItemStatus(ctx, it.ID, domain.ItemOwned); rerr != nil {
			w.log.Error("withdraw rollback failed", "item", it.ID, "err", rerr)
		}
		return err
	}
	return w.db.Credit(ctx, u.ID, 0, domain.HistoryWithdraw, "Вывод: "+itemTitle(it))
}

func (w *Wallet) deliver(ctx context.Context, u domain.User, it domain.Item) error {
	net, err := w.nets.Get(ctx, u.ServerID)
	if err != nil {
		return err
	}
	// At is the item's creation time, not wall clock: retries after a failed
	// delivery must send an identical purchase_date under the same command id.
	op := network.Op{Key: "item:" + strconv.FormatInt(it.ID, 10), Reason: "Deltament withdraw", At: it.CreatedAt, Value: it.Value}
	switch it.Kind {
	case domain.ItemUsername:
		return net.GrantUsername(ctx, op, u.ServerUserID, it.Name)
	case domain.ItemNumber:
		return net.GrantNumber(ctx, op, u.ServerUserID, it.Name)
	case domain.ItemGift:
		return net.GrantGift(ctx, op, u.ServerUserID, it.GiftID)
	case domain.ItemStars:
		return net.GrantStars(ctx, op, u.ServerUserID, it.Amount)
	}
	return fmt.Errorf("%w: unknown item kind %s", domain.ErrInvalid, it.Kind)
}

func itemTitle(it domain.Item) string {
	switch it.Kind {
	case domain.ItemGift:
		return fmt.Sprintf("%s #%d", it.Title, it.Num)
	case domain.ItemNumber:
		return it.Name
	}
	return it.Title
}

/* ------------------------------ TON card ------------------------------ */

// TONCard returns the account's TON payout card. The card is created on first
// open, so every account always has a destination for rewards.
func (w *Wallet) TONCard(ctx context.Context, u domain.User) (domain.TONCard, error) {
	addr, err := w.db.EnsureTONCard(ctx, u.ServerUserID)
	if err != nil {
		return domain.TONCard{}, err
	}
	balance, pending, stars, err := w.db.TONBalances(ctx, u.ServerUserID)
	if err != nil {
		return domain.TONCard{}, err
	}
	return domain.TONCard{Address: addr, Balance: balance, Pending: pending, Stars: stars}, nil
}

// SetTONAddress replaces the card's payout address.
func (w *Wallet) SetTONAddress(ctx context.Context, u domain.User, address string) error {
	return w.db.SetTONAddress(ctx, u.ServerUserID, address)
}

// TONHistory returns the card's TON movements.
func (w *Wallet) TONHistory(ctx context.Context, u domain.User) ([]domain.TONEntry, error) {
	return w.db.TONLedger(ctx, u.ServerUserID, 50)
}

// WithdrawTON is the new payout rail: earned GRAM is moved onto the TON card
// instead of being converted into stars. One GRAM is one TON, in nanotons.
func (w *Wallet) WithdrawTON(ctx context.Context, u domain.User, amount domain.Cents) (int64, error) {
	if amount < 1 {
		return 0, fmt.Errorf("%w: amount must be positive", domain.ErrInvalid)
	}
	// Cents are 1/100 GRAM, so one cent is 1e7 nanotons.
	nanoton := int64(amount) * (domain.NanotonPerTON / 100)
	whole := float64(nanoton) / float64(domain.NanotonPerTON)
	title := fmt.Sprintf("Вывод в GRAM · %s GRAM", trimTON(whole))
	if err := w.db.InTx(ctx, func(tx store.Tx) error {
		return tx.Debit(ctx, u.ID, amount, domain.HistoryWithdraw, title)
	}); err != nil {
		return 0, err
	}
	if err := w.db.CreditTON(ctx, u.ServerUserID, nanoton, "withdraw", "Начисление на GRAM-карту"); err != nil {
		if rerr := w.db.Credit(ctx, u.ID, amount, domain.HistoryRefund, "Возврат вывода"); rerr != nil {
			w.log.Error("ton withdraw refund failed", "user", u.ID, "amount", amount, "err", rerr)
		}
		return 0, err
	}
	return nanoton, nil
}

// RequestTONPayout queues a send from the card. The amount leaves the balance
// when the request is created; if the owner rejects it the store refunds it.
func (w *Wallet) RequestTONPayout(ctx context.Context, u domain.User, amountNanoton int64, address, note string) (domain.TONPayout, error) {
	if amountNanoton <= 0 {
		return domain.TONPayout{}, fmt.Errorf("%w: amount must be positive", domain.ErrInvalid)
	}
	// Deltament never stores or shows a wallet address. The request is queued
	// without one; the owner asks the user for the wallet in Telegram. This is
	// what makes wallet-to-wallet transfers impossible from the app.
	return w.db.CreateTONPayout(ctx, u.ServerUserID, "", amountNanoton, note)
}

// GrantTON is the admin path: it credits the card directly from the admin panel
// or the bot, mirroring a star grant.
func (w *Wallet) GrantTON(ctx context.Context, mainUserID int64, amountNanoton int64, actor string) (domain.TONCard, error) {
	if mainUserID <= 0 || amountNanoton <= 0 {
		return domain.TONCard{}, fmt.Errorf("%w: user and amount are required", domain.ErrInvalid)
	}
	if amountNanoton > 1_000_000*domain.NanotonPerTON {
		return domain.TONCard{}, fmt.Errorf("%w: amount is too large", domain.ErrInvalid)
	}
	title := "Начисление GRAM от администратора"
	if actor != "" {
		title += " · " + actor
	}
	if err := w.db.CreditTON(ctx, mainUserID, amountNanoton, "admin_grant", title); err != nil {
		return domain.TONCard{}, err
	}
	addr, err := w.db.EnsureTONCard(ctx, mainUserID)
	if err != nil {
		return domain.TONCard{}, err
	}
	balance, pending, stars, err := w.db.TONBalances(ctx, mainUserID)
	if err != nil {
		return domain.TONCard{}, err
	}
	return domain.TONCard{Address: addr, Balance: balance, Pending: pending, Stars: stars}, nil
}

// trimTON keeps at most two decimals in log titles without a float tail.
func trimTON(v float64) string {
	s := strconv.FormatFloat(v, 'f', 2, 64)
	for len(s) > 0 && s[len(s)-1] == '0' {
		s = s[:len(s)-1]
	}
	if len(s) > 0 && s[len(s)-1] == '.' {
		s = s[:len(s)-1]
	}
	return s
}

// TONPayouts lists the owner's send queue.
func (w *Wallet) TONPayouts(ctx context.Context, status string) ([]domain.TONPayout, error) {
	return w.db.ListTONPayouts(ctx, status)
}

// SetTONPayoutStatus marks a queued send as sent or rejected.
func (w *Wallet) SetTONPayoutStatus(ctx context.Context, id int64, status string) error {
	return w.db.SetTONPayoutStatus(ctx, id, status)
}

// TONOrders lists the bot's TON purchase queue.
func (w *Wallet) TONOrders(ctx context.Context) ([]domain.TONOrder, error) {
	return w.db.PendingTONOrders(ctx)
}

// SetTONOrderStatus advances a purchase order and credits the card once.
func (w *Wallet) SetTONOrderStatus(ctx context.Context, id int64, status string) (domain.TONOrder, error) {
	return w.db.SetTONOrderStatus(ctx, id, status)
}

// ResolveUserByMainID maps the messenger id shown in chat to its account.
func (w *Wallet) ResolveUserByMainID(ctx context.Context, mainUserID int64) (domain.User, error) {
	return w.db.ResolveTONUser(ctx, mainUserID)
}
