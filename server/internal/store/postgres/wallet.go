package postgres

import (
	"context"

	"telesrv/internal/store/postgres/sqlcgen"
)

// WalletStore reads the Deltament GRAM card that is also shown in the
// DeltaGram client's wallet card. Deltament tables live in the same Postgres
// database as the Telegram server tables.
type WalletStore struct {
	db sqlcgen.DBTX
}

func NewWalletStore(db sqlcgen.DBTX) *WalletStore {
	return &WalletStore{db: db}
}

// Card returns the card payout address and balance in nanotons. A user without
// a Deltament card yet gets zeroes, so the client renders an empty card instead
// of an internal error.
func (s *WalletStore) Card(ctx context.Context, userID int64) (string, int64, error) {
	var address string
	var balance int64
	err := s.db.QueryRow(ctx, `
		SELECT COALESCE(c.address, ''), COALESCE(tb.balance_nanoton, 0)
		FROM (SELECT $1::bigint AS user_id) u
		LEFT JOIN deltament_ton_cards c ON c.user_id = u.user_id
		LEFT JOIN ton_balances tb ON tb.user_id = u.user_id`, userID).Scan(&address, &balance)
	if err != nil {
		return "", 0, err
	}
	return address, balance, nil
}
