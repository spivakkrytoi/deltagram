package giftpack

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"strings"
	"testing"
	"time"

	"telesrv/internal/domain"
)

func zipPack(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var out bytes.Buffer
	w := zip.NewWriter(&out)
	for name, body := range files {
		f, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := f.Write([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func TestZIPRejectsUnsafeEntries(t *testing.T) {
	for _, name := range []string{"../gift.tgs", "/gift.tgs", "a\\gift.tgs", "a/../gift.tgs"} {
		t.Run(name, func(t *testing.T) {
			_, err := NewZipAssetResolver(zipPack(t, map[string]string{"pack.json": `{}`, name: "animation"}))
			if err == nil {
				t.Fatal("unsafe ZIP entry accepted")
			}
		})
	}
	var out bytes.Buffer
	w := zip.NewWriter(&out)
	for i := 0; i < 2; i++ {
		f, _ := w.Create("pack.json")
		_, _ = f.Write([]byte(`{}`))
	}
	_ = w.Close()
	if _, err := NewZipAssetResolver(out.Bytes()); err == nil {
		t.Fatal("duplicate pack.json accepted")
	}
}

func TestManifestRejectsUnknownFieldsAndDuplicateTitles(t *testing.T) {
	for _, body := range []string{
		`{"pack_name":"demo","gifts":[{"title":"A","stars":10,"convert_stars":1,"base_animation":"a.tgs","surprise":true}]}`,
		`{"pack_name":"demo","gifts":[{"title":"A","stars":10,"convert_stars":1,"base_animation":"a.tgs"},{"title":"a","stars":10,"convert_stars":1,"base_animation":"b.tgs"}]}`,
		`{"pack_name":"demo","gifts":[{"title":"A","stars":10,"convert_stars":1,"base_animation":"../a.tgs"}]}`,
	} {
		if _, err := ParseManifest([]byte(body)); err == nil {
			t.Fatalf("invalid manifest accepted: %s", body)
		}
	}
}

func TestManifestRejectsDuplicateUpgradePrefixes(t *testing.T) {
	base := `{"title":"%s","stars":10,"convert_stars":1,"base_animation":"%s","upgrade":{"upgrade_stars":5,"supply_total":10,"slug_prefix":"Same","models":[{"name":"M","animation":"m.tgs","permille":500}],"patterns":[{"name":"P","animation":"p.tgs","permille":500}],"backdrops":[{"name":"B","center":"#000000","edge":"#000000","pattern":"#000000","text":"#ffffff","permille":500}]}}`
	body := fmt.Sprintf(`{"pack_name":"demo","gifts":[`+base+`,`+base+`]}`, "A", "a.tgs", "B", "b.tgs")
	if _, err := ParseManifest([]byte(body)); err == nil || !strings.Contains(err.Error(), "duplicate upgrade slug_prefix") {
		t.Fatalf("duplicate slug prefixes: %v", err)
	}
}

type testService struct {
	existing []domain.StarGift
	writes   []domain.StarGiftCatalogBundleWrite
	failAt   int
}

func (s *testService) CatalogAll(context.Context) ([]domain.StarGift, error) { return s.existing, nil }
func (s *testService) PrepareAnimation(name string, data []byte) (domain.StarGiftAnimation, error) {
	if string(data) == "invalid" {
		return domain.StarGiftAnimation{}, fmt.Errorf("invalid animation")
	}
	hash := sha256.Sum256(data)
	return domain.StarGiftAnimation{SourceName: name, JSON: data, TGS: data, SHA256: hash[:], Width: 512, Height: 512}, nil
}
func (s *testService) CreateCatalogBundle(_ context.Context, w domain.StarGiftCatalogBundleWrite) (domain.StarGiftCatalogBundleResult, error) {
	if s.failAt > 0 && len(s.writes)+1 == s.failAt {
		return domain.StarGiftCatalogBundleResult{}, fmt.Errorf("store failure")
	}
	s.writes = append(s.writes, w)
	id := int64(len(s.writes) + 100)
	s.existing = append(s.existing, domain.StarGift{ID: id, Title: w.Catalog.Title})
	return domain.StarGiftCatalogBundleResult{Catalog: domain.StarGiftCatalogEntry{Gift: domain.StarGift{ID: id}}}, nil
}

func testManifest(t *testing.T) Manifest {
	t.Helper()
	models := []AttrSpec{{Name: "M1", Animation: "m1.tgs", Permille: 500}, {Name: "M2", Animation: "m2.tgs", Permille: 500}}
	patterns := []AttrSpec{{Name: "P1", Animation: "p1.tgs", Permille: 500}, {Name: "P2", Animation: "p2.tgs", Permille: 500}}
	backdrops := []BackdropSpec{{Name: "B1", Center: "#112233", Edge: "#112233", Pattern: "#112233", Text: "#ffffff", Permille: 500}, {Name: "B2", Center: "#334455", Edge: "#334455", Pattern: "#334455", Text: "#ffffff", Permille: 500}}
	return Manifest{PackName: "Demo", Gifts: []GiftSpec{{Title: "Gift A", Stars: 50, ConvertStars: 10, BaseAnimation: "base.tgs", Upgrade: &UpgradeSpec{UpgradeStars: 25, SupplyTotal: 100, SlugPrefix: "gift-a", Models: models, Patterns: patterns, Backdrops: backdrops}}}}
}

func testAssets() MapAssetResolver {
	assets := MapAssetResolver{}
	for _, name := range []string{"base.tgs", "m1.tgs", "m2.tgs", "p1.tgs", "p2.tgs"} {
		assets[name] = []byte(name)
	}
	return assets
}

func TestImportPreviewPublishAndRepeat(t *testing.T) {
	svc := &testService{}
	manifest := testManifest(t)
	opts := ImportOptions{DryRun: true, Actor: "operator", CommandID: "pack-command", Now: func() time.Time { return time.Unix(1_700_000_000, 0) }}
	preview, err := Import(context.Background(), svc, manifest, testAssets(), opts)
	if err != nil || len(svc.writes) != 0 || preview.Gifts[0].Status != "would_create" {
		t.Fatalf("preview: %+v, %v", preview, err)
	}
	opts.DryRun = false
	created, err := Import(context.Background(), svc, manifest, testAssets(), opts)
	if err != nil || created.Gifts[0].Status != "created" || created.Gifts[0].GiftID != "101" || len(svc.writes) != 1 {
		t.Fatalf("create: %+v, %v", created, err)
	}
	if svc.writes[0].Collectible == nil || svc.writes[0].Collectible.CommandID != opts.CommandID || len(svc.writes[0].Collectible.Models) != 2 {
		t.Fatal("collectible pool not published with catalog bundle")
	}
	repeat, err := Import(context.Background(), svc, manifest, testAssets(), opts)
	if err != nil || repeat.Gifts[0].Status != "skipped" || len(svc.writes) != 1 {
		t.Fatalf("repeat: %+v, %v", repeat, err)
	}
}

func TestImportPreflightsEntirePackBeforeWrite(t *testing.T) {
	svc := &testService{}
	manifest := testManifest(t)
	bad := manifest.Gifts[0]
	bad.Title = "Gift B"
	bad.BaseAnimation = "bad.tgs"
	manifest.Gifts = append(manifest.Gifts, bad)
	assets := testAssets()
	assets["bad.tgs"] = []byte("invalid")
	_, err := Import(context.Background(), svc, manifest, assets, ImportOptions{CommandID: "pack-command"})
	if err == nil || !strings.Contains(err.Error(), "Gift B") || len(svc.writes) != 0 {
		t.Fatalf("preflight: %v, writes=%d", err, len(svc.writes))
	}
}

func TestImportAppliesPackScheduleAndPerGiftWindowWins(t *testing.T) {
	now := time.Date(2026, 11, 27, 10, 0, 0, 0, time.UTC) // 12:00 Europe/Kyiv
	clock := func() time.Time { return now }

	oneShot := int(now.Add(2 * time.Hour).Unix())
	svc := &testService{}
	if _, err := Import(context.Background(), svc, testManifest(t), testAssets(), ImportOptions{
		Now:             clock,
		Actor:           "operator",
		CommandID:       "pack-command",
		LockedUntilDate: oneShot,
	}); err != nil {
		t.Fatalf("one-shot import: %v", err)
	}
	if got := svc.writes[0].Catalog.LockedUntilDate; got != oneShot {
		t.Fatalf("locked_until_date = %d, want %d", got, oneShot)
	}
	if svc.writes[0].Catalog.PurchaseWindowEnabled {
		t.Fatal("one-shot release must not enable a recurring window")
	}

	// A per-gift purchase_window in pack.json wins over the pack-level schedule.
	perGift := testManifest(t)
	perGift.Gifts[0].PurchaseWindow = &PurchaseWindowSpec{Start: "21:40", DurationMinutes: 60, Timezone: "Europe/Kyiv"}
	svc = &testService{}
	if _, err := Import(context.Background(), svc, perGift, testAssets(), ImportOptions{
		Now:             clock,
		Actor:           "operator",
		CommandID:       "pack-command",
		LockedUntilDate: oneShot,
	}); err != nil {
		t.Fatalf("per-gift import: %v", err)
	}
	if !svc.writes[0].Catalog.PurchaseWindowEnabled {
		t.Fatal("per-gift window was not applied")
	}
	nextOpening := time.Date(2026, 11, 27, 19, 40, 0, 0, time.UTC).Unix() // 21:40 Kyiv (EET, UTC+2)
	if got := svc.writes[0].Catalog.LockedUntilDate; got != int(nextOpening) {
		t.Fatalf("per-gift locked_until_date = %d, want %d", got, nextOpening)
	}

	// A pack-level daily window seeds the next Kyiv opening for clients that only
	// understand the one-shot lock.
	svc = &testService{}
	if _, err := Import(context.Background(), svc, testManifest(t), testAssets(), ImportOptions{
		Now:                           clock,
		Actor:                         "operator",
		CommandID:                     "pack-command",
		PurchaseWindowEnabled:         true,
		PurchaseWindowStartMinute:     21*60 + 40,
		PurchaseWindowDurationMinutes: 60,
		PurchaseWindowTimezone:        "Europe/Kyiv",
	}); err != nil {
		t.Fatalf("daily import: %v", err)
	}
	if got := svc.writes[0].Catalog.LockedUntilDate; got != int(nextOpening) {
		t.Fatalf("daily window locked_until_date = %d, want %d", got, nextOpening)
	}

	if _, err := Import(context.Background(), &testService{}, testManifest(t), testAssets(), ImportOptions{
		Now:             clock,
		Actor:           "operator",
		CommandID:       "pack-command",
		LockedUntilDate: int(now.Unix()) - 1,
	}); err == nil {
		t.Fatal("past one-shot schedule accepted")
	}
}
