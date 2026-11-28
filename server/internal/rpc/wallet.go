package rpc

import (
	"context"

	"github.com/iamxvbaba/td/tg"
	"github.com/iamxvbaba/td/tlprofile"
)

// registerWallet exposes the Deltament GRAM card through the wallet RPCs the
// DeltaGram clients already call in profile → cards.
func (r *Router) registerWallet(d *tlprofile.Dispatcher) {
	registerRPC[*tg.WalletGetStateRequest](d, tlprofile.SemanticMethodWalletGetState, func(ctx context.Context, _ *tg.WalletGetStateRequest) (any, error) {
		if r.deps.Wallet == nil {
			return nil, notImplementedErr()
		}
		userID, _, err := r.currentUserID(ctx)
		if err != nil {
			return nil, err
		}
		address, balance, err := r.deps.Wallet.Card(ctx, userID)
		if err != nil {
			return nil, internalErr()
		}
		return &tg.WalletState{Address: address, Balance: balance}, nil
	})
	registerRPC[*tg.WalletGetExistingWaltBalanceRequest](d, tlprofile.SemanticMethodWalletGetExistingWaltBalance, func(ctx context.Context, _ *tg.WalletGetExistingWaltBalanceRequest) (any, error) {
		// The client only uses this to decide whether to show a legacy Walt
		// balance link; DeltaGram has no such balance.
		return &tg.WalletExistingBalance{}, nil
	})
}
