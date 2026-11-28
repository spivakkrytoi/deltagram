// Adapted from owpengram-server dev@55c3e84 under Apache-2.0; see NOTICE.md.
package giftpack

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"telesrv/internal/domain"
)

// Preparer normalizes a raw animation file into the canonical form the
// domain write structs require. *stargifts.Service satisfies this as-is.
type Preparer interface {
	PrepareAnimation(fileName string, data []byte) (domain.StarGiftAnimation, error)
}

// Service is the minimal stargifts.Service surface Import needs.
// *stargifts.Service satisfies it as-is.
type Service interface {
	Preparer
	CatalogAll(ctx context.Context) ([]domain.StarGift, error)
	CreateCatalogBundle(ctx context.Context, write domain.StarGiftCatalogBundleWrite) (domain.StarGiftCatalogBundleResult, error)
}

// ImportOptions configures one Import call.
type ImportOptions struct {
	// DryRun stops just short of CreateCatalogBundle: every asset is still
	// resolved and PrepareAnimation'd (so a bad file is still caught), and
	// lifecycle validation still runs, but nothing is written to the store.
	DryRun    bool
	Actor     string
	CommandID string
	// Now overrides the clock (tests only); defaults to time.Now.
	Now func() time.Time

	// Pack-level release schedule. A per-gift purchase_window in pack.json takes
	// precedence over these fields. LockedUntilDate is a one-time unlock in Unix
	// seconds; PurchaseWindow* is a recurring daily window evaluated in
	// PurchaseWindowTimezone (Europe/Kyiv by default).
	LockedUntilDate               int
	PurchaseWindowEnabled         bool
	PurchaseWindowStartMinute     int
	PurchaseWindowDurationMinutes int
	PurchaseWindowTimezone        string

	// Pack-level defaults for gifts that do not declare their own supply or
	// per-user cap in pack.json. A per-gift value always wins.
	AvailabilityTotal int
	PerUserTotal      int
}

// GiftImportOutcome reports what happened to one gift in the pack.
type GiftImportOutcome struct {
	Title  string `json:"title"`
	Status string `json:"status"` // created | would_create | skipped | failed | not_attempted
	Error  string `json:"error,omitempty"`
	GiftID string `json:"gift_id,omitempty"`
}

// ImportResult is the full outcome of one Import call, one entry per gift in
// the manifest, in manifest order.
type ImportResult struct {
	PackName string              `json:"pack_name"`
	Gifts    []GiftImportOutcome `json:"gifts"`
}

// Import prevalidates the whole pack, then publishes each new gift with its
// collectible pool atomically. A failed write stops the import; a new command
// may resume it because already published titles are reported as skipped.
func Import(ctx context.Context, svc Service, manifest Manifest, assets AssetResolver, opts ImportOptions) (ImportResult, error) {
	now := time.Now
	if opts.Now != nil {
		now = opts.Now
	}
	at := int(now().Unix())
	if err := validatePackSchedule(opts, at); err != nil {
		return ImportResult{}, err
	}
	existing, err := svc.CatalogAll(ctx)
	if err != nil {
		return ImportResult{}, fmt.Errorf("read existing catalog: %w", err)
	}
	existingTitles := make(map[string]bool, len(existing))
	for _, g := range existing {
		existingTitles[strings.ToLower(strings.TrimSpace(g.Title))] = true
	}

	result := ImportResult{PackName: manifest.PackName}
	type preparedGift struct {
		bundle domain.StarGiftCatalogBundleWrite
		skip   bool
	}
	prepared := make([]preparedGift, len(manifest.Gifts))
	// Validate every gift and asset before the first database write. A malformed
	// later entry cannot leave an earlier entry published.
	for i, spec := range manifest.Gifts {
		outcome := GiftImportOutcome{Title: spec.Title}
		bundle, err := buildBundle(svc, spec, assets)
		if err != nil {
			return ImportResult{}, fmt.Errorf("gift %q: %w", spec.Title, err)
		}
		applyPurchaseWindow(&bundle.Catalog, spec.PurchaseWindow, at)
		applyPackSchedule(&bundle.Catalog, spec.PurchaseWindow, opts, at)
		applyPackLimits(&bundle.Catalog, opts)
		if bundle.Catalog.Animation.Width != 512 || bundle.Catalog.Animation.Height != 512 ||
			len(bundle.Catalog.Animation.TGS) == 0 || len(bundle.Catalog.Animation.SHA256) != 32 {
			return ImportResult{}, fmt.Errorf("gift %q: invalid base animation", spec.Title)
		}
		if err := bundle.Catalog.ValidateLifecycleAuthoring(at); err != nil {
			return ImportResult{}, fmt.Errorf("gift %q: %w", spec.Title, err)
		}
		bundle.Catalog.NormalizeLifecycleAuthoring(at)
		bundle.Catalog.Actor, bundle.Catalog.CommandID = opts.Actor, opts.CommandID
		if bundle.Collectible != nil {
			bundle.Collectible.Actor, bundle.Collectible.CommandID = opts.Actor, opts.CommandID
			validation := *bundle.Collectible
			validation.GiftID = 1
			if err := domain.ValidateStarGiftCollectibleDraft(validation); err != nil {
				return ImportResult{}, fmt.Errorf("gift %q: %w", spec.Title, err)
			}
		}
		prepared[i] = preparedGift{bundle: bundle, skip: existingTitles[strings.ToLower(strings.TrimSpace(spec.Title))]}
		if prepared[i].skip {
			outcome.Status = "skipped"
		} else {
			outcome.Status = "would_create"
		}
		result.Gifts = append(result.Gifts, outcome)
	}
	newCount := 0
	for _, item := range prepared {
		if !item.skip {
			newCount++
		}
	}
	if len(existing)+newCount > domain.MaxStarGiftCatalogSize {
		return ImportResult{}, fmt.Errorf("gift pack exceeds catalog capacity of %d", domain.MaxStarGiftCatalogSize)
	}
	if opts.DryRun {
		return result, nil
	}
	for i, item := range prepared {
		if item.skip {
			continue
		}
		created, err := svc.CreateCatalogBundle(ctx, item.bundle)
		if err != nil {
			result.Gifts[i].Status, result.Gifts[i].Error = "failed", err.Error()
			for j := i + 1; j < len(result.Gifts); j++ {
				if result.Gifts[j].Status == "would_create" {
					result.Gifts[j].Status = "not_attempted"
				}
			}
			return result, fmt.Errorf("gift %q: %w", manifest.Gifts[i].Title, err)
		}
		result.Gifts[i].Status, result.Gifts[i].GiftID = "created", strconv.FormatInt(created.Catalog.Gift.ID, 10)
	}
	return result, nil
}

// applyPurchaseWindow copies a pack's recurring daily window into the catalog
// write and seeds LockedUntilDate with the next opening so clients that only
// understand the one-shot lock still show a useful release time.
func applyPurchaseWindow(write *domain.StarGiftCatalogWrite, window *PurchaseWindowSpec, now int) {
	if window == nil {
		return
	}
	startMinute, err := window.StartMinute()
	if err != nil {
		return
	}
	if _, err := time.LoadLocation(window.NormalizedTimezone()); err != nil {
		return
	}
	write.PurchaseWindowEnabled = true
	write.PurchaseWindowStartMinute = startMinute
	write.PurchaseWindowDurationMinutes = window.DurationMinutes
	write.PurchaseWindowTimezone = window.NormalizedTimezone()
	seedPurchaseWindowLock(write, now)
}

// applyPackSchedule applies the admin-supplied schedule to gifts that did not
// carry their own purchase_window in pack.json. A per-gift window always wins.
func applyPackSchedule(write *domain.StarGiftCatalogWrite, perGift *PurchaseWindowSpec, opts ImportOptions, now int) {
	if write.Auction || perGift != nil {
		return
	}
	if opts.PurchaseWindowEnabled {
		timezone := strings.TrimSpace(opts.PurchaseWindowTimezone)
		if timezone == "" {
			timezone = "Europe/Kyiv"
		}
		write.PurchaseWindowEnabled = true
		write.PurchaseWindowStartMinute = opts.PurchaseWindowStartMinute
		write.PurchaseWindowDurationMinutes = opts.PurchaseWindowDurationMinutes
		write.PurchaseWindowTimezone = timezone
		seedPurchaseWindowLock(write, now)
		return
	}
	if opts.LockedUntilDate > now {
		write.LockedUntilDate = opts.LockedUntilDate
	}
}

// seedPurchaseWindowLock records the next opening as a one-shot locked_until_date.
// Clients that only know locked_until_date then draw a lock and a local release
// time even though the server enforces the recurring window on every day.
func seedPurchaseWindowLock(write *domain.StarGiftCatalogWrite, now int) {
	location, err := time.LoadLocation(write.PurchaseWindowTimezone)
	if err != nil || write.PurchaseWindowDurationMinutes >= 1440 {
		return
	}
	nowTime := time.Unix(int64(now), 0).In(location)
	minute := nowTime.Hour()*60 + nowTime.Minute()
	offset := (minute - write.PurchaseWindowStartMinute + 1440) % 1440
	if offset < write.PurchaseWindowDurationMinutes {
		return
	}
	startHour, startMinute := write.PurchaseWindowStartMinute/60, write.PurchaseWindowStartMinute%60
	next := time.Date(nowTime.Year(), nowTime.Month(), nowTime.Day(), startHour, startMinute, 0, 0, location)
	if !next.After(nowTime) {
		next = next.AddDate(0, 0, 1)
	}
	write.LockedUntilDate = int(next.Unix())
}

// applyPackLimits applies the admin form's pack-wide supply and per-user caps
// only when the gift itself did not declare them in pack.json.
func applyPackLimits(write *domain.StarGiftCatalogWrite, opts ImportOptions) {
	if write.Auction {
		return
	}
	if opts.AvailabilityTotal > 0 && !write.Limited {
		write.Limited = true
		write.AvailabilityTotal = opts.AvailabilityTotal
	}
	if opts.PerUserTotal > 0 && !write.LimitedPerUser {
		write.LimitedPerUser = true
		write.PerUserTotal = opts.PerUserTotal
	}
}

func validatePackSchedule(opts ImportOptions, now int) error {
	if opts.AvailabilityTotal < 0 || opts.PerUserTotal < 0 {
		return fmt.Errorf("pack limits must not be negative")
	}
	if opts.LockedUntilDate != 0 {
		if opts.LockedUntilDate <= now {
			return fmt.Errorf("pack schedule: release time must be in the future")
		}
		if opts.PurchaseWindowEnabled {
			return fmt.Errorf("pack schedule: choose either a one-time release or a recurring window")
		}
	}
	if !opts.PurchaseWindowEnabled {
		return nil
	}
	if opts.PurchaseWindowStartMinute < 0 || opts.PurchaseWindowStartMinute > 1439 {
		return fmt.Errorf("pack schedule: start minute must be within one day")
	}
	if opts.PurchaseWindowDurationMinutes < 1 || opts.PurchaseWindowDurationMinutes > 1440 {
		return fmt.Errorf("pack schedule: duration must be 1..1440 minutes")
	}
	timezone := strings.TrimSpace(opts.PurchaseWindowTimezone)
	if timezone == "" {
		timezone = "Europe/Kyiv"
	}
	if _, err := time.LoadLocation(timezone); err != nil {
		return fmt.Errorf("pack schedule: timezone is invalid")
	}
	return nil
}

func buildBundle(prep Preparer, spec GiftSpec, assets AssetResolver) (domain.StarGiftCatalogBundleWrite, error) {
	baseData, err := assets.Open(spec.BaseAnimation)
	if err != nil {
		return domain.StarGiftCatalogBundleWrite{}, err
	}
	baseAnim, err := prep.PrepareAnimation(spec.BaseAnimation, baseData)
	if err != nil {
		return domain.StarGiftCatalogBundleWrite{}, fmt.Errorf("gift %q: base animation: %w", spec.Title, err)
	}
	bundle := domain.StarGiftCatalogBundleWrite{
		Catalog: domain.StarGiftCatalogWrite{
			Title:                spec.Title,
			Stars:                spec.Stars,
			ConvertStars:         spec.ConvertStars,
			Enabled:              true,
			Animation:            baseAnim,
			Limited:              spec.Limited,
			Birthday:             spec.Birthday,
			RequirePremium:       spec.RequirePremium,
			SupportOnly:          spec.SupportOnly,
			LimitedPerUser:       spec.LimitedPerUser,
			Auction:              spec.Auction,
			AvailabilityTotal:    spec.AvailabilityTotal,
			ResellMinStars:       spec.ResellMinStars,
			PerUserTotal:         spec.PerUserTotal,
			AuctionSlug:          spec.AuctionSlug,
			GiftsPerRound:        spec.GiftsPerRound,
			AuctionStartDate:     spec.AuctionStartDate,
			AuctionRoundDuration: spec.AuctionRoundDuration,
		},
	}
	if spec.Upgrade == nil {
		return bundle, nil
	}
	models, err := buildAttributes(prep, spec.Title, domain.StarGiftCollectibleModel, spec.Upgrade.Models, assets)
	if err != nil {
		return domain.StarGiftCatalogBundleWrite{}, err
	}
	patterns, err := buildAttributes(prep, spec.Title, domain.StarGiftCollectiblePattern, spec.Upgrade.Patterns, assets)
	if err != nil {
		return domain.StarGiftCatalogBundleWrite{}, err
	}
	backdrops, err := buildBackdrops(spec.Title, spec.Upgrade.Backdrops)
	if err != nil {
		return domain.StarGiftCatalogBundleWrite{}, err
	}
	bundle.Collectible = &domain.StarGiftCollectibleWrite{
		UpgradeStars: spec.Upgrade.UpgradeStars,
		SupplyTotal:  spec.Upgrade.SupplyTotal,
		SlugPrefix:   spec.Upgrade.SlugPrefix,
		Models:       models,
		Patterns:     patterns,
		Backdrops:    backdrops,
		CommandID:    "giftpack-preview",
	}
	return bundle, nil
}

func buildAttributes(prep Preparer, giftTitle string, kind domain.StarGiftCollectibleAttributeKind, specs []AttrSpec, assets AssetResolver) ([]domain.StarGiftCollectibleAttribute, error) {
	out := make([]domain.StarGiftCollectibleAttribute, 0, len(specs))
	for i, a := range specs {
		data, err := assets.Open(a.Animation)
		if err != nil {
			return nil, err
		}
		anim, err := prep.PrepareAnimation(a.Animation, data)
		if err != nil {
			return nil, fmt.Errorf("gift %q: %s %q: %w", giftTitle, kind, a.Name, err)
		}
		rarity := domain.StarGiftAttributeRarityKind(strings.TrimSpace(a.Rarity))
		if rarity == "" {
			rarity = domain.StarGiftRarityPermille
		}
		out = append(out, domain.StarGiftCollectibleAttribute{
			Kind:           kind,
			Name:           a.Name,
			RarityKind:     rarity,
			RarityPermille: a.Permille,
			Crafted:        a.Crafted,
			SortOrder:      i,
			Animation:      &anim,
		})
	}
	return out, nil
}

func buildBackdrops(giftTitle string, specs []BackdropSpec) ([]domain.StarGiftCollectibleAttribute, error) {
	out := make([]domain.StarGiftCollectibleAttribute, 0, len(specs))
	for i, b := range specs {
		center, err := parseHexColor(b.Center)
		if err != nil {
			return nil, fmt.Errorf("gift %q: backdrop %q: center: %w", giftTitle, b.Name, err)
		}
		edge, err := parseHexColor(b.Edge)
		if err != nil {
			return nil, fmt.Errorf("gift %q: backdrop %q: edge: %w", giftTitle, b.Name, err)
		}
		pattern, err := parseHexColor(b.Pattern)
		if err != nil {
			return nil, fmt.Errorf("gift %q: backdrop %q: pattern: %w", giftTitle, b.Name, err)
		}
		text, err := parseHexColor(b.Text)
		if err != nil {
			return nil, fmt.Errorf("gift %q: backdrop %q: text: %w", giftTitle, b.Name, err)
		}
		out = append(out, domain.StarGiftCollectibleAttribute{
			Kind:           domain.StarGiftCollectibleBackdrop,
			Name:           b.Name,
			BackdropID:     i + 1,
			CenterColor:    center,
			EdgeColor:      edge,
			PatternColor:   pattern,
			TextColor:      text,
			RarityKind:     domain.StarGiftRarityPermille,
			RarityPermille: b.Permille,
			SortOrder:      i,
		})
	}
	return out, nil
}

// parseHexColor parses a "#RRGGBB" (or bare "RRGGBB") string into a packed
// 24-bit int, the form domain.StarGiftCollectibleAttribute's color fields want.
func parseHexColor(s string) (int, error) {
	s = strings.TrimPrefix(strings.TrimSpace(s), "#")
	if len(s) != 6 {
		return 0, fmt.Errorf("invalid color %q, want #RRGGBB", s)
	}
	v, err := strconv.ParseInt(s, 16, 32)
	if err != nil {
		return 0, fmt.Errorf("invalid color %q: %w", s, err)
	}
	return int(v), nil
}
