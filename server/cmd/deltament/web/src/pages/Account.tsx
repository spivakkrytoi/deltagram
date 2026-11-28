import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { motion } from 'motion/react'
import { ArrowDownLeftIcon, ArrowUpRightIcon, ArrowUUpLeftIcon, AtIcon, BroadcastIcon, CopyIcon, DeviceMobileIcon, GavelIcon, ImageSquareIcon, TrophyIcon, MonitorIcon, PackageIcon, PencilSimpleIcon, PlusIcon, SignInIcon, SignOutIcon, TelegramLogoIcon, TrashIcon, UploadSimpleIcon } from '@phosphor-icons/react'
import clsx from 'clsx'
import { fmt, fmtAgo, fmtDate, fmtUsd } from '../lib/format'
import { itemName, useStore, type InvItem } from '../lib/store'
import { lotUrl } from '../lib/data'
import { GiftArt } from '../components/GiftArt'
import { Badge, Button, Dropdown, Empty, Gram, GramIcon, Modal, Segmented, softSpring, Stagger } from '../components/ui'
import { ItemArt } from '../components/art'
import { useInventory, useServers, type GiftAsset } from '../lib/market'
import { api } from '../lib/api'

function SignInGate({ text }: { text: string }) {
  const { openAuth } = useStore()
  return (
    <div className="mx-auto max-w-md pt-6">
      <Empty
        icon={<SignInIcon className="size-6" />}
        title="Требуется вход"
        text={text}
        action={
          <Button onClick={openAuth}>
            <TelegramLogoIcon className="size-4" /> Войти
          </Button>
        }
      />
    </div>
  )
}

type Tab = 'all' | 'gift' | 'number' | 'username' | 'stars'

export function InventoryPage() {
  const { user, inventory, cancelListing, toast } = useStore()
  const [tab, setTab] = useState<Tab>('all')
  const [selected, setSelected] = useState<InvItem | null>(null)
  const [listing, setListing] = useState<ListingTarget | null>(null)
  const [giftFilters, setGiftFilters] = useState({ collection: 'all', model: 'all', backdrop: 'all', symbol: 'all' })
  const [giftLimit, setGiftLimit] = useState(24)
  const { data: assets } = useInventory(!!user)

  if (!user) return <SignInGate text="Войдите, чтобы увидеть свои подарки, номера и юзернеймы" />

  const items = inventory.filter((i) => tab === 'all' || i.kind === tab)
  const total = inventory.reduce((s, i) => s + i.value, 0)
  const channels = assets?.channels ?? []
  const usernames = assets?.usernames ?? []
  const listings = assets?.listings ?? []
  const gifts = assets?.gifts ?? []
  // The tabs must filter every inventory section, not just the purchased-item
  // grid. Assets owned in DeltaGram and lots currently listed live in separate
  // arrays, so each tab decides which of those sections is rendered at all.
  const showOwnedAssets = tab === 'all' || tab === 'username'
  const showGifts = tab === 'all' || tab === 'gift'
  const visibleListings = listings.filter((lot) => tab === 'all' || lot.kind === tab)

  // Фильтры по реальным подаркам: коллекция (название каталога), модель, фон
  // и символ. Один и тот же подарок может быть закрытым или открытым — это
  // не влияет на возможность выставить его на маркет.
  const optionsFor = (pick: (g: GiftAsset) => string) =>
    Array.from(new Set(gifts.map(pick).map((v) => v.trim()).filter(Boolean))).sort((a, b) => a.localeCompare(b, 'ru'))
  const giftCollections = optionsFor((g) => g.title)
  const giftModels = optionsFor((g) => g.model?.name ?? '')
  const giftBackdrops = optionsFor((g) => g.backdrop?.name ?? '')
  const giftSymbols = optionsFor((g) => g.symbol?.name ?? '')
  const filteredGifts = gifts.filter(
    (g) =>
      (giftFilters.collection === 'all' || g.title === giftFilters.collection) &&
      (giftFilters.model === 'all' || g.model?.name === giftFilters.model) &&
      (giftFilters.backdrop === 'all' || g.backdrop?.name === giftFilters.backdrop) &&
      (giftFilters.symbol === 'all' || g.symbol?.name === giftFilters.symbol),
  )
  // "Nothing here" has to reflect the active tab across every section.
  const tabEmpty =
    items.length === 0 &&
    (!showOwnedAssets || (channels.length === 0 && usernames.length === 0)) &&
    (!showGifts || filteredGifts.length === 0) &&
    visibleListings.length === 0

  return (
    <div>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="display text-[26px] leading-none sm:text-[34px]">Инвентарь</h1>
          <p className="mt-1 flex items-center gap-1.5 text-sm text-muted">
            {inventory.length} предметов · оценка <Gram value={Math.round(total * 10) / 10} className="font-semibold text-fg" size="size-3" />
          </p>
        </div>
        <div className="no-scrollbar -mx-4 w-[calc(100%+2rem)] overflow-x-auto px-4 sm:mx-0 sm:w-auto sm:px-0">
          <Segmented
            value={tab}
            onChange={setTab}
            options={[
              { value: 'all', label: 'Все' },
              { value: 'gift', label: 'Подарки' },
              { value: 'number', label: 'Номера' },
              { value: 'username', label: 'Юзернеймы' },
              { value: 'stars', label: 'Звёзды' },
            ]}
          />
        </div>
      </div>

      {showOwnedAssets && (channels.length > 0 || usernames.length > 0) && (
        <section className="mb-6">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
            <div>
              <h2 className="text-[17px] font-semibold">Ваши активы в DeltaGram</h2>
              <p className="text-xs text-muted">Каналы и коллекционные юзернеймы можно выставить на аукцион</p>
            </div>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {channels.map((ch) => (
              <div key={`channel-${ch.id}`} className="card flex items-center gap-3 rounded-[10px] p-4">
                <span className="grid size-10 shrink-0 place-items-center rounded-full bg-accent/15 text-accent">
                  <BroadcastIcon className="size-5" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[14px] font-semibold">{ch.title || 'Канал'}</p>
                  <p className="truncate text-xs text-link">@{ch.username}</p>
                </div>
                <Button
                  variant="glass"
                  onClick={() => setListing({ kind: 'channel', channelId: ch.id, username: ch.username, title: ch.title || `@${ch.username}` })}
                >
                  Выставить
                </Button>
              </div>
            ))}
            {usernames.map((asset) => (
              <div key={`username-${asset.id}`} className="card flex items-center gap-3 rounded-[10px] p-4">
                <span className="grid size-10 shrink-0 place-items-center rounded-full bg-white/6 text-muted">
                  <AtIcon className="size-5" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[14px] font-semibold">@{asset.username}</p>
                  <p className="text-xs text-subtle">Коллекционный юзернейм</p>
                </div>
                <Button
                  variant="glass"
                  onClick={() => setListing({ kind: 'collectible', usernameAssetId: asset.id, username: asset.username, title: `@${asset.username}` })}
                >
                  Выставить
                </Button>
              </div>
            ))}
          </div>
        </section>
      )}

      {showGifts && gifts.length > 0 && (
        <section className="mb-6">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
            <div>
              <h2 className="text-[17px] font-semibold">Подарки в профиле</h2>
              <p className="text-xs text-muted">
                {gifts.length} подарков · выставлять на продажу можно только улучшенные NFT-подарки
              </p>
            </div>
          </div>
          <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Dropdown
              label="Коллекция"
              value={giftFilters.collection}
              options={[{ value: 'all', label: 'Все коллекции' }, ...giftCollections.map((v) => ({ value: v, label: v }))]}
              onChange={(v) => setGiftFilters((f) => ({ ...f, collection: v }))}
            />
            <Dropdown
              label="Модель"
              value={giftFilters.model}
              options={[{ value: 'all', label: 'Все модели' }, ...giftModels.map((v) => ({ value: v, label: v }))]}
              onChange={(v) => setGiftFilters((f) => ({ ...f, model: v }))}
            />
            <Dropdown
              label="Фон"
              value={giftFilters.backdrop}
              options={[{ value: 'all', label: 'Все фоны' }, ...giftBackdrops.map((v) => ({ value: v, label: v }))]}
              onChange={(v) => setGiftFilters((f) => ({ ...f, backdrop: v }))}
            />
            <Dropdown
              label="Символ"
              value={giftFilters.symbol}
              options={[{ value: 'all', label: 'Все символы' }, ...giftSymbols.map((v) => ({ value: v, label: v }))]}
              onChange={(v) => setGiftFilters((f) => ({ ...f, symbol: v }))}
            />
          </div>
          {filteredGifts.length === 0 ? (
            <p className="rounded-[10px] border border-line px-4 py-6 text-center text-sm text-muted">Под эти фильтры подарков нет</p>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                {filteredGifts.slice(0, giftLimit).map((g) => (
                  <div key={g.rowId} className="card overflow-hidden rounded-[12px]">
                    <div className="relative">
                      <GiftArt art={g.art} slug={g.slug} num={g.num} title={`${g.title} #${g.num}`} className="aspect-square w-full" />
                      <span className="absolute top-2 left-2">
                        {g.unique ? <Badge tone="success">NFT</Badge> : <Badge tone="default">Обычный</Badge>}
                      </span>
                    </div>
                    <div className="p-3">
                      <p className="truncate text-[13px] font-semibold">
                        {g.title} #{g.num}
                      </p>
                      <div className="mt-1.5 flex flex-wrap gap-1">
                        {[g.model?.name, g.backdrop?.name, g.symbol?.name].filter(Boolean).map((trait) => (
                          <span key={trait} className="max-w-full truncate rounded-full bg-white/[0.06] px-2 py-0.5 text-[10px] text-muted">
                            {trait}
                          </span>
                        ))}
                      </div>
                      <Button
                        variant="glass"
                        size="sm"
                        className="mt-2.5 w-full"
                        disabled={!g.unique}
                        title={g.unique ? undefined : 'Выставлять можно только улучшенные NFT-подарки'}
                        onClick={() => setListing({ kind: 'gift', giftRowId: g.rowId, username: '', title: `${g.title} #${g.num}` })}
                      >
                        <GavelIcon className="size-3.5" /> {g.unique ? 'Выставить' : 'Только NFT'}
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
              {filteredGifts.length > giftLimit && (
                <Button variant="glass" className="mt-3 w-full" onClick={() => setGiftLimit((n) => n + 24)}>
                  Показать ещё
                </Button>
              )}
            </>
          )}
        </section>
      )}

      {visibleListings.length > 0 && (
        <section className="mb-6">
          <h2 className="mb-3 text-[17px] font-semibold">Мои лоты</h2>
          <div className="grid gap-2">
            {visibleListings.map((lot) => (
              <div key={lot.id} className="card flex items-center gap-3 rounded-[10px] px-4 py-3">
                <GavelIcon className="size-5 shrink-0 text-muted" />
                <Link to={lotUrl(lot)} className="min-w-0 flex-1 transition-opacity hover:opacity-80">
                  <p className="truncate text-[14px] font-semibold">
                    {lot.kind === 'gift' ? lot.name : `@${lot.name}`}
                  </p>
                  <p className="text-xs text-subtle">
                    {lot.status === 'auction' ? `до ${fmtDate(lot.endsAt)}` : lot.status === 'sold' ? 'Продан' : 'Продаётся'} ·{' '}
                    {lot.status === 'auction' ? 'старт' : 'цена'} <Gram value={lot.startPrice} size="size-3" className="text-xs" />
                  </p>
                </Link>
                {lot.status !== 'sold' && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      cancelListing(lot.id).then((ok) => ok && toast(lot.status === 'auction' ? 'Лот снят с аукциона' : 'Лот снят с продажи', 'success'))
                    }
                  >
                    Снять
                  </Button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {tabEmpty ? (
        <Empty
          icon={<PackageIcon className="size-6" />}
          title="Здесь пока пусто"
          text="Покупайте юзернеймы, номера и подарки на маркете"
          action={
            <Link to="/usernames">
              <Button>На маркет</Button>
            </Link>
          }
        />
      ) : (
        <motion.div key={tab} initial="hidden" animate="show" variants={{ show: { transition: { staggerChildren: 0.03 } } }} className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {items.map((item) => (
            <motion.button
              key={item.id}
              variants={{ hidden: { opacity: 0, y: 14, scale: 0.96 }, show: { opacity: 1, y: 0, scale: 1, transition: softSpring } }}
              whileTap={{ scale: 0.97 }}
              onClick={() => setSelected(item)}
              className="card group overflow-hidden rounded-[14px] text-left"
            >
              <ItemArt item={item} className="transition-transform duration-700 group-hover:scale-[1.05]" />
              <div className="p-3">
                <p className="truncate text-[14px] font-semibold tabular">{itemName(item)}</p>
                <div className="mt-1 flex items-center justify-between text-xs">
                  <Gram value={item.value} className="font-semibold" size="size-3" />
                  <span className="text-subtle">{item.source}</span>
                </div>
              </div>
            </motion.button>
          ))}
        </motion.div>
      )}

      <Modal open={!!selected} onClose={() => setSelected(null)}>
        {selected && <ItemDetails item={selected} onClose={() => setSelected(null)} />}
      </Modal>

      <ListingModal target={listing} onClose={() => setListing(null)} />
    </div>
  )
}

type ListingTarget = {
  kind: 'channel' | 'collectible' | 'gift'
  channelId?: string
  usernameAssetId?: string
  giftRowId?: string
  username: string
  title: string
}

function ListingModal({ target, onClose }: { target: ListingTarget | null; onClose: () => void }) {
  const { createListing, listGift, toast } = useStore()
  const [mode, setMode] = useState<'auction' | 'sale'>('auction')
  const [price, setPrice] = useState(10)
  const [hours, setHours] = useState(24)
  const [busy, setBusy] = useState(false)

  // Gifts are fixed-price only: the marketplace no longer runs gift auctions,
  // so force the sale mode and hide the auction/sale toggle for gifts.
  const isGift = target?.kind === 'gift'
  const effectiveMode: 'auction' | 'sale' = isGift ? 'sale' : mode

  const submit = async () => {
    if (!target) return
    setBusy(true)
    const res =
      target.kind === 'gift'
        ? await listGift({ gift_id: Number(target.giftRowId), price, mode: 'sale', auction_hours: hours })
        : await createListing({
            asset_kind: target.kind,
            channel_id: target.channelId,
            username_asset_id: target.usernameAssetId,
            username: target.username,
            price,
            mode,
            auction_hours: hours,
          })
    setBusy(false)
    if (!res) return
    toast(effectiveMode === 'auction' ? 'Лот выставлен на аукцион' : 'Лот выставлен на продажу', 'success')
    onClose()
  }

  return (
    <Modal open={!!target} onClose={onClose}>
      <div className="p-6 pt-10">
        <div className="text-center">
          <span className="mx-auto grid size-14 place-items-center rounded-full bg-accent/15 text-accent">
            <GavelIcon className="size-7" />
          </span>
          <h3 className="mt-4 text-[22px] font-bold">{effectiveMode === 'auction' ? 'Выставить на аукцион' : 'Выставить на продажу'}</h3>
          <p className="mt-1 text-sm text-muted">
            {target?.kind === 'gift' ? target.title : `${target?.title} · @${target?.username}`}
          </p>
        </div>
        {!isGift && (
          <div className="mt-6">
            <Segmented
              value={mode}
              onChange={setMode}
              options={[
                { value: 'auction', label: 'Аукцион' },
                { value: 'sale', label: 'Фикс. цена' },
              ]}
            />
          </div>
        )}
        {isGift && <p className="mt-5 rounded-[10px] bg-white/[0.04] px-4 py-3 text-center text-[13px] text-muted">Подарки продаются только по фиксированной цене</p>}
        <label className="mt-5 block text-xs font-semibold text-muted">{effectiveMode === 'auction' ? 'Стартовая цена, GRAM' : 'Цена, GRAM'}</label>
        <input
          type="number"
          min={1}
          max={100000}
          step={1}
          value={price}
          onChange={(e) => setPrice(Math.min(100000, Math.max(1, Number(e.target.value))))}
          className="mt-1.5 w-full rounded-2xl bg-white/5 px-4 py-3 text-[16px] font-semibold outline-none ring-1 ring-white/10 focus:ring-accent"
        />
        <p className="mt-1.5 text-xs text-subtle">Максимум — 100 000 GRAM.</p>
        {effectiveMode === 'auction' && (
          <>
            <label className="mt-4 block text-xs font-semibold text-muted">Длительность</label>
            <div className="mt-1.5 grid grid-cols-3 gap-2">
              {[
                { h: 24, label: '1 день' },
                { h: 72, label: '3 дня' },
                { h: 168, label: '7 дней' },
              ].map((d) => (
                <button
                  key={d.h}
                  onClick={() => setHours(d.h)}
                  className={clsx(
                    'rounded-2xl px-3 py-2.5 text-sm font-semibold transition-colors',
                    hours === d.h ? 'bg-accent/15 text-fg ring-[1.5px] ring-accent' : 'bg-white/5 text-muted hover:bg-white/8',
                  )}
                >
                  {d.label}
                </button>
              ))}
            </div>
          </>
        )}
        <p className="mt-4 text-xs leading-relaxed text-subtle">
          {target?.kind === 'gift'
            ? 'Подарок сразу перейдёт на служебный аккаунт DeltaMent relayer и будет храниться там до покупки. Снять лот можно до первой ставки или покупки.'
            : 'После продажи канал будет удалён, а юзернейм перейдёт покупателю как коллекционный. Снять лот можно до первой ставки или покупки.'}
        </p>
        <Button size="lg" className="mt-5 w-full" disabled={busy} onClick={submit}>
          {busy ? 'Публикуем…' : effectiveMode === 'auction' ? `Выставить за ${fmt(price)} GRAM` : `Продать за ${fmt(price)} GRAM`}
        </Button>
      </div>
    </Modal>
  )
}

function ItemDetails({ item, onClose }: { item: InvItem; onClose: () => void }) {
  const { sellItem, withdrawItem, claimGift, toast } = useStore()
  const [busy, setBusy] = useState(false)
  const isUsername = item.kind === 'username'
  // Marketplace gifts already exist on the relayer account: they can only be
  // moved to the profile, not minted again or sold back.
  const escrowedGift = item.kind === 'gift' && !!item.giftRowId

  const act = async (fn: () => Promise<boolean>) => {
    setBusy(true)
    const ok = await fn()
    setBusy(false)
    if (ok) onClose()
  }
  return (
    <div className="p-5 pt-12">
      <div className="mx-auto w-60 overflow-hidden rounded-[30px] shadow-[0_24px_60px_-20px_rgba(0,0,0,0.7)] ring-1 ring-white/12">
        <ItemArt item={item} big />
      </div>
      <div className="pt-6 text-center">
        <h3 className="text-[22px] font-bold tabular">{itemName(item)}</h3>
        <p className="mt-1 text-sm text-muted">
          Получен {fmtDate(item.ts)} · {item.source}
        </p>
        <p className="mt-1 text-sm text-subtle">
          Цена покупки: <Gram value={item.value} size="size-3" className="font-semibold text-fg" />
        </p>
        <div className={clsx('mt-6 grid gap-2', !escrowedGift && 'sm:grid-cols-2')}>
          {escrowedGift ? (
            <Button
              size="lg"
              disabled={busy}
              onClick={() =>
                act(async () => {
                  const ok = await claimGift(item.id)
                  if (ok) toast('Подарок добавлен в профиль DeltaGram', 'success')
                  return ok
                })
              }
            >
              <PlusIcon className="size-4" /> Добавить в профиль
            </Button>
          ) : (
            <>
              <Button
                variant="glass"
                size="lg"
                disabled={busy}
                onClick={() =>
                  act(async () => {
                    const res = await sellItem(item.id)
                    if (res) toast(`Лот выставлен за ${fmt(res.price)} GRAM — GRAM придёт на карту после покупки`, 'success')
                    return res !== null
                  })
                }
              >
                Выставить на продажу · <Gram value={item.value} />
              </Button>
              <Button
                size="lg"
                disabled={busy}
                onClick={() =>
                  act(async () => {
                    const ok = await withdrawItem(item.id)
                    if (ok) toast(isUsername ? 'Юзернейм привязан к аккаунту DeltaGram' : 'Выдано на ваш аккаунт DeltaGram', 'success')
                    return ok
                  })
                }
              >
                {isUsername ? <AtIcon className="size-4" /> : <ArrowUpRightIcon className="size-4" />}
                {isUsername ? 'Привязать к аккаунту DeltaGram' : 'Вывести'}
              </Button>
            </>
          )}
        </div>
        {escrowedGift && (
          <p className="mt-3 text-xs leading-relaxed text-subtle">
            Подарок хранится на служебном аккаунте DeltaMent relayer и перейдёт на ваш профиль после нажатия кнопки.
          </p>
        )}
      </div>
    </div>
  )
}

const HISTORY_ICON = { win: TrophyIcon, buy: PackageIcon, sell: ArrowDownLeftIcon, topup: ArrowDownLeftIcon, bid: GavelIcon, refund: ArrowUUpLeftIcon, withdraw: ArrowUpRightIcon }
const TOPUP = [100, 500, 1000, 5000]

export function ProfilePage() {
  const { user, server, balance, ton, tonPending, stars, inventory, history, sessions, currentSessionId, kickSession, kickOtherSessions, logout, toast } = useStore()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [topup, setTopup] = useState(false)
  const [withdraw, setWithdraw] = useState(false)
  const [editProfile, setEditProfile] = useState(false)
  const [sendTON, setSendTON] = useState(false)
  const [gramCard, setGramCard] = useState(false)
  const [buyTON, setBuyTON] = useState(false)
  const [tonLog, setTonLog] = useState(false)
  const { data: servers } = useServers()
  const rate = servers?.find((s) => s.slug === server)?.starsPerGram ?? servers?.[0]?.starsPerGram ?? 0

  useEffect(() => {
    if (params.get('topup')) {
      setTopup(true)
      setParams({}, { replace: true })
    }
  }, [params, setParams])

  if (!user) return <SignInGate text="Войдите, чтобы открыть профиль и баланс" />

  const orderedSessions = [...sessions].sort((a, b) => {
    if (a.id === currentSessionId) return -1
    if (b.id === currentSessionId) return 1
    return b.lastSeen - a.lastSeen
  })

  const stats = [
    { label: 'Предметов', value: fmt(inventory.length) },
    { label: 'Операций', value: fmt(history.length) },
    { label: 'Сеансов', value: fmt(sessions.length) },
  ]

  return (
    <div className="mx-auto max-w-[640px] space-y-3">
      <Stagger i={0} className="pb-4 text-center">
        <div className="relative mx-auto w-full max-w-[560px]">
          <div className="relative h-32 overflow-hidden rounded-[16px] ring-1 ring-line sm:h-40">
            {user.banner ? (
              <img src={user.banner} alt="" className="size-full object-cover" />
            ) : (
              <div className="size-full bg-surface [background-image:radial-gradient(120%_120%_at_18%_0%,rgba(224,80,74,0.38),transparent_58%),radial-gradient(120%_120%_at_100%_100%,rgba(122,38,55,0.55),transparent_62%)]" />
            )}
            <div className="absolute inset-0 bg-gradient-to-t from-bg/85 via-bg/10 to-transparent" />
            <button
              onClick={() => setEditProfile(true)}
              className="absolute top-2.5 right-2.5 inline-flex h-8 items-center gap-1.5 rounded-full bg-black/45 px-3 text-[12px] font-semibold text-white backdrop-blur transition-colors hover:bg-black/65"
            >
              <PencilSimpleIcon className="size-3.5" /> Редактировать профиль
            </button>
          </div>
          <span className="relative mx-auto -mt-12 grid size-24 place-items-center rounded-full bg-accent text-4xl font-bold ring-4 ring-bg">
            {user.username[0].toUpperCase()}
          </span>
        </div>
        <h1 className="display mt-4 max-w-full break-words text-[24px] leading-tight sm:text-[30px]">{user.name}</h1>
        <button
          onClick={() => {
            navigator.clipboard?.writeText(`@${user.username}`).catch(() => {})
            toast('Юзернейм скопирован')
          }}
          className="mt-0.5 flex items-center gap-1.5 text-[15px] text-link"
        >
          @{user.username} <CopyIcon className="size-3.5" />
        </button>
        <p className="mt-1 text-xs text-subtle tabular">{user.phone.replace(/\d(?=(?:\D*\d){4})/g, '•')}</p>
      </Stagger>

      <Stagger i={1}>
        <div className="card rounded-[16px] p-5">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <p className="text-xs uppercase tracking-wide text-muted">Карта DeltaGram</p>
              <p className="mt-1 text-[26px] font-bold tabular">{fmt(ton)} <span className="text-sm text-muted">GRAM</span></p>
              <p className="text-[15px] tabular text-muted">{fmt(stars)} <span className="text-xs">⭐</span>{tonPending > 0 ? ` · в отправке ${fmt(tonPending)} GRAM` : ''}</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setGramCard(true)}>
                <ArrowUpRightIcon className="size-4" /> Вывести GRAM
              </Button>
              <Button variant="glass" onClick={() => setSendTON(true)} disabled={ton <= 0}>
                <ArrowUpRightIcon className="size-4" /> С карты
              </Button>
              <Button variant="glass" onClick={() => setWithdraw(true)} disabled={rate <= 0}>
                <ArrowUpRightIcon className="size-4" /> В звёзды
              </Button>
            </div>
          </div>
          <p className="mt-3 text-xs text-subtle">Карточки и их оформление живут в клиенте DeltaGram: профиль → карты. Здесь только вывод и пополнение.</p>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button onClick={() => setTopup(true)}>
            <ArrowDownLeftIcon className="size-4" /> Пополнить GRAM
          </Button>
          <Button variant="glass" onClick={() => setWithdraw(true)} disabled={rate <= 0}>
            <ArrowUpRightIcon className="size-4" /> В звёзды
          </Button>
          <Button variant="glass" onClick={() => navigate('/inventory')}>
            <PackageIcon className="size-4" /> Инвентарь
          </Button>
          <span className="ml-auto text-[12px] text-subtle tabular">
            Баланс маркета: <Gram value={balance} size="size-3" className="text-muted" />
          </span>
        </div>
      </Stagger>

      <Stagger i={2} className="grid grid-cols-3 gap-3">
        {stats.map((s) => (
          <div key={s.label} className="card rounded-[14px] px-4 py-3.5">
            <p className="text-[22px] font-bold tabular">{s.value}</p>
            <p className="text-xs text-muted">{s.label}</p>
          </div>
        ))}
      </Stagger>

      <Stagger i={3} className="card overflow-hidden rounded-[26px]">
        <h2 className="px-5 pt-5 pb-2 text-[15px] font-semibold">История операций</h2>
        {history.length === 0 ? (
          <p className="px-5 pb-5 text-sm text-muted">Операций пока нет</p>
        ) : (
          <div className="pb-2">
            {history.map((h) => {
              const Icon = HISTORY_ICON[h.kind]
              return (
                <div key={h.id} className="flex items-center gap-3 px-5 py-2.5">
                  <span className="grid size-9 shrink-0 place-items-center rounded-full bg-white/6 text-muted">
                    <Icon className="size-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[14px] font-medium">{h.title}</p>
                    <p className="text-xs text-subtle">{fmtAgo(h.ts)}</p>
                  </div>
                  {h.amount !== 0 && (
                    <span className={clsx('shrink-0 text-[14px] font-semibold tabular', h.amount > 0 ? 'text-success' : 'text-fg')}>
                      {h.amount > 0 ? '+' : '−'}
                      {fmt(Math.abs(h.amount))}
                    </span>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </Stagger>

      <Stagger i={4} className="card overflow-hidden rounded-[26px]">
        <div className="flex items-center justify-between gap-3 px-5 pt-5 pb-3">
          <h2 className="text-[15px] font-semibold">Активные сеансы</h2>
          {orderedSessions.length > 1 && (
            <button
              onClick={() => {
                kickOtherSessions().then((r) => r !== null && toast('Другие сеансы завершены', 'success'))
              }}
              className="text-xs font-semibold text-danger transition-colors hover:text-danger/80"
            >
              Завершить другие
            </button>
          )}
        </div>
        {orderedSessions.length === 0 ? (
          <p className="px-5 pb-5 text-sm text-muted">Нет активных сеансов</p>
        ) : (
          orderedSessions.map((s) => {
            const current = s.id === currentSessionId
            const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(s.device)
            return (
              <div key={s.id} className="flex items-center gap-3 border-t border-white/5 px-5 py-3">
                <span className="grid size-9 shrink-0 place-items-center rounded-full bg-white/6 text-muted">
                  {mobile ? <DeviceMobileIcon className="size-4" /> : <MonitorIcon className="size-4" />}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[14px] font-semibold">{s.device}</p>
                  <p className="truncate text-xs text-subtle">
                    {s.place} · {current ? 'сейчас' : fmtAgo(s.lastSeen)}
                  </p>
                </div>
                {current ? (
                  <span className="shrink-0 rounded-full bg-success/10 px-2.5 py-1 text-[11px] font-semibold text-success">Текущий</span>
                ) : (
                  <button
                    onClick={() => {
                      kickSession(s.id).then((r) => r !== null && toast('Сеанс завершён', 'success'))
                    }}
                    className="shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold text-danger transition-colors hover:bg-danger/10"
                  >
                    Завершить
                  </button>
                )}
              </div>
            )
          })
        )}
      </Stagger>

      <Stagger i={5}>
        <Button
          variant="danger"
          size="lg"
          className="w-full"
          onClick={() => {
            logout().then(() => navigate('/usernames'))
          }}
        >
          <SignOutIcon className="size-4" /> Выйти
        </Button>
      </Stagger>

      <TopUpSheet open={topup} onClose={() => setTopup(false)} />
      <WithdrawSheet open={withdraw} onClose={() => setWithdraw(false)} rate={rate} balance={balance} />
      <ProfileEditSheet open={editProfile} onClose={() => setEditProfile(false)} />
      <GramCardSheet open={gramCard} onClose={() => setGramCard(false)} balance={balance} />
      <TONSendSheet open={sendTON} onClose={() => setSendTON(false)} ton={ton} />
      <TONBuySheet open={buyTON} onClose={() => setBuyTON(false)} />
      <TONHistorySheet open={tonLog} onClose={() => setTonLog(false)} />
    </div>
  )
}

function ProfileEditSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { user, saveBanner, removeBanner, toast } = useStore()
  const inputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) {
      setFile(null)
      setPreview('')
      setError('')
      setBusy(false)
    }
  }, [open])

  // Освобождаем object URL, когда превью меняется или лист закрывается.
  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview)
    }
  }, [preview])

  const pick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0]
    e.target.value = ''
    if (!picked) return
    setError('')
    if (picked.type !== 'image/jpeg' && picked.type !== 'image/png') {
      setError('Подойдёт только JPEG или PNG.')
      return
    }
    if (picked.size > 4 * 1024 * 1024) {
      setError('Файл больше 4 МБ.')
      return
    }
    const url = URL.createObjectURL(picked)
    try {
      const dims = await new Promise<{ w: number; h: number }>((resolve, reject) => {
        const img = new Image()
        img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight })
        img.onerror = () => reject(new Error('decode'))
        img.src = url
      })
      if (dims.w < 320 || dims.h < 100) {
        URL.revokeObjectURL(url)
        setError('Минимум 320×100 пикселей.')
        return
      }
      if (dims.w > 6000 || dims.h > 6000) {
        URL.revokeObjectURL(url)
        setError('Максимум 6000 пикселей по стороне.')
        return
      }
      setFile(picked)
      setPreview(url)
    } catch {
      URL.revokeObjectURL(url)
      setError('Не удалось прочитать изображение.')
    }
  }

  const submit = async () => {
    if (!file) return
    setBusy(true)
    const res = await saveBanner(file)
    setBusy(false)
    if (!res) return
    toast('Баннер профиля обновлён', 'success')
    onClose()
  }

  const clear = async () => {
    setBusy(true)
    const ok = await removeBanner()
    setBusy(false)
    if (!ok) return
    toast('Баннер удалён', 'success')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose}>
      <div className="p-6 pt-10">
        <div className="text-center">
          <span className="mx-auto grid size-14 place-items-center rounded-full bg-accent/15 text-accent">
            <ImageSquareIcon className="size-7" />
          </span>
          <h3 className="mt-4 text-[22px] font-bold">Баннер профиля</h3>
          <p className="mt-1 text-sm text-muted">Картинка заменит цветной фон под аватаром и будет видна всем в DeltaGram.</p>
        </div>
        <div className="mt-5 overflow-hidden rounded-[14px] ring-1 ring-line">
          <div className="relative h-36 w-full bg-surface">
            {preview || user?.banner ? (
              <img src={preview || user?.banner} alt="" className="size-full object-cover" />
            ) : (
              <div className="size-full [background-image:radial-gradient(120%_120%_at_18%_0%,rgba(224,80,74,0.38),transparent_58%),radial-gradient(120%_120%_at_100%_100%,rgba(122,38,55,0.55),transparent_62%)]" />
            )}
          </div>
        </div>
        <input ref={inputRef} type="file" accept="image/jpeg,image/png" className="hidden" onChange={pick} />
        <div className="mt-4 grid gap-2 sm:grid-cols-2">
          <Button variant="glass" onClick={() => inputRef.current?.click()} disabled={busy}>
            <UploadSimpleIcon className="size-4" /> {preview ? 'Другое фото' : 'Выбрать фото'}
          </Button>
          <Button variant="ghost" onClick={clear} disabled={busy || !user?.banner}>
            <TrashIcon className="size-4" /> Удалить
          </Button>
        </div>
        {error && <p className="mt-2 text-xs text-danger">{error}</p>}
        <p className="mt-2 text-[11px] text-subtle">JPEG или PNG · до 4 МБ · от 320×100 px</p>
        <Button size="lg" className="mt-4 w-full" disabled={busy || !file} onClick={submit}>
          {busy ? 'Сохраняем…' : 'Сохранить баннер'}
        </Button>
      </div>
    </Modal>
  )
}

function WithdrawSheet({ open, onClose, rate, balance }: { open: boolean; onClose: () => void; rate: number; balance: number }) {
  const { withdrawStars, toast } = useStore()
  const [amount, setAmount] = useState(1)
  const [loading, setLoading] = useState(false)
  const stars = Math.round(amount * rate)
  const tooMuch = amount > balance

  const submit = async () => {
    setLoading(true)
    const res = await withdrawStars(amount)
    setLoading(false)
    if (!res) return
    toast(`Выведено ${fmt(res.stars)} ⭐ на аккаунт DeltaGram`, 'success')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6 text-center">
        <span className="mx-auto grid size-16 place-items-center rounded-full bg-accent/15 text-accent">
          <ArrowUpRightIcon className="size-8" />
        </span>
        <h3 className="mt-4 text-[22px] font-bold">Вывести в звёзды</h3>
        <p className="mt-1 text-sm text-muted">
          {rate > 0 ? `1 GRAM = ${fmt(rate)} ⭐ · спишем с баланса маркета` : 'Вывод на этом сервере выключен'}
        </p>
        <div className="mt-6 flex items-center justify-center gap-3">
          <input
            type="number"
            min={1}
            max={Math.max(1, balance)}
            step={1}
            value={amount}
            onChange={(e) => setAmount(Math.max(1, Number(e.target.value)))}
            className="w-36 rounded-2xl bg-white/5 px-4 py-3 text-center text-[22px] font-bold outline-none ring-1 ring-white/10 focus:ring-accent"
          />
          <span className="text-sm font-semibold text-muted">GRAM</span>
        </div>
        <p className="mt-2 text-sm text-subtle">
          Получите <span className="font-semibold text-fg">{fmt(stars)} ⭐</span>
        </p>
        <p className="mt-1 text-xs text-subtle">
          Доступно: {fmt(balance)} GRAM
        </p>
        <Button size="lg" className="mt-6 w-full" onClick={submit} disabled={loading || rate <= 0 || tooMuch || amount < 1}>
          {loading ? 'Обработка…' : tooMuch ? 'Недостаточно GRAM' : `Вывести ${fmt(stars)} ⭐`}
        </Button>
      </div>
    </Modal>
  )
}

function TopUpSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { topUp, toast, server } = useStore()
  const { data: servers } = useServers()
  const [amount, setAmount] = useState(500)
  const [loading, setLoading] = useState(false)
  const rate = servers?.find((s) => s.slug === server)?.starsPerGram ?? 0

  const submit = async () => {
    setLoading(true)
    const res = await topUp(amount)
    setLoading(false)
    if (!res) return
    toast(`Баланс пополнен на ${fmt(res.credited)} GRAM`, 'success')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6 text-center">
        <motion.div initial={{ scale: 0.5, rotate: -20 }} animate={{ scale: 1, rotate: 0 }} transition={{ type: 'spring', bounce: 0.5, duration: 0.7 }} className="mx-auto w-fit">
          <GramIcon className="size-16 drop-shadow-[0_10px_30px_rgba(62,164,255,0.6)]" />
        </motion.div>
        <h3 className="mt-4 text-[22px] font-bold">Пополнить баланс</h3>
        <p className="mt-1 text-sm text-muted">
          {rate > 0 ? `Спишем звёзды с вашего аккаунта в приватке · ${fmt(Math.ceil(amount * rate))} ⭐` : 'На этом сервере пополнение выключено'}
        </p>
        <div className="mt-6 grid grid-cols-2 gap-2">
          {TOPUP.map((v) => (
            <button
              key={v}
              onClick={() => setAmount(v)}
              className={clsx('relative flex h-16 flex-col items-center justify-center rounded-[10px] transition-colors', amount === v ? 'bg-accent/15 text-fg ring-[1.5px] ring-accent' : 'bg-white/5 text-muted hover:bg-white/8')}
            >
              <Gram value={v} className="relative text-[17px] font-bold" size="size-4" />
              <span className="relative text-[11px] text-subtle">~ {fmtUsd(v)}</span>
            </button>
          ))}
        </div>
        <Button size="lg" className="mt-6 w-full" onClick={submit} disabled={loading || rate <= 0}>
          {loading ? 'Обработка…' : `Пополнить на ${fmt(amount)} GRAM`}
        </Button>
      </div>
    </Modal>
  )
}

/* ------------------------------ TON sheets ------------------------------ */

function GramCardSheet({ open, onClose, balance }: { open: boolean; onClose: () => void; balance: number }) {
  const { withdrawTON, toast } = useStore()
  const [amount, setAmount] = useState(1)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (open) {
      setAmount(balance >= 1 ? Math.floor(balance) : 1)
      setLoading(false)
    }
  }, [open, balance])

  const tooMuch = amount > balance
  const submit = async () => {
    setLoading(true)
    const res = await withdrawTON(amount)
    setLoading(false)
    if (!res) return
    toast(`${fmt(res.credited)} GRAM зачислено на карту DeltaGram`, 'success')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6 text-center">
        <span className="mx-auto grid size-16 place-items-center rounded-full bg-accent/15 text-accent">
          <GramIcon className="size-9" />
        </span>
        <h3 className="mt-4 text-[22px] font-bold">Вывести GRAM на карту</h3>
        <p className="mt-1 text-sm text-muted">1 GRAM = 1 GRAM на карте DeltaGram. Сумма появится на карте сразу.</p>
        <div className="mt-6 flex items-center justify-center gap-3">
          <input
            type="number"
            min={1}
            max={Math.max(1, balance)}
            step={1}
            value={amount}
            onChange={(e) => setAmount(Math.max(1, Number(e.target.value)))}
            className="w-36 rounded-2xl bg-white/5 px-4 py-3 text-center text-[22px] font-bold outline-none ring-1 ring-white/10 focus:ring-accent"
          />
          <span className="text-sm font-semibold text-muted">GRAM</span>
        </div>
        <p className="mt-1 text-xs text-subtle">Доступно: {fmt(balance)} GRAM</p>
        <Button size="lg" className="mt-6 w-full" onClick={submit} disabled={loading || tooMuch || amount < 1}>
          {loading ? 'Обработка…' : tooMuch ? 'Недостаточно GRAM' : `На карту · ${fmt(amount)} GRAM`}
        </Button>
      </div>
    </Modal>
  )
}

function TONSendSheet({ open, onClose, ton }: { open: boolean; onClose: () => void; ton: number }) {
  const { requestTONPayout, setTONAddress, toast } = useStore()
  const [amount, setAmount] = useState(1)
  const [note, setNote] = useState('')
  const [address, setAddress] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (open) {
      setAmount(ton >= 1 ? Math.min(ton, Math.round(ton * 100) / 100) : 1)
      setNote('')
      setLoading(false)
      api<{ address?: string }>('/api/wallet/ton').then((card) => setAddress(card?.address ?? '')).catch(() => {})
    }
  }, [open, ton])

  const tooMuch = amount > ton
  const submit = async () => {
    setLoading(true)
    if (address.trim()) {
      const saved = await setTONAddress(address.trim())
      if (!saved) { setLoading(false); return }
    }
    const res = await requestTONPayout(amount, note)
    setLoading(false)
    if (!res) return
    toast('Заявка на отправку GRAM создана', 'success')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6">
        <div className="text-center">
          <span className="mx-auto grid size-16 place-items-center rounded-full bg-[#0098EA]/15">
            <GramIcon className="size-9" />
          </span>
          <h3 className="mt-4 text-[22px] font-bold">Отправка GRAM с карты</h3>
          <p className="mt-1 text-sm text-muted">Заявка уйдёт владельцу. Он напишет вам в Telegram, уточнит кошелёк и отправит GRAM.</p>
        </div>
        <label className="mt-5 block">
          <span className="mb-1.5 block text-[12px] font-semibold text-muted">Сумма</span>
          <div className="flex items-center gap-2 rounded-[12px] bg-white/5 px-4 py-3 ring-1 ring-white/10 focus-within:ring-accent">
            <input
              type="number"
              min={0.01}
              step={0.01}
              value={amount}
              onChange={(e) => setAmount(Math.max(0, Number(e.target.value)))}
              className="min-w-0 flex-1 bg-transparent text-[22px] font-bold outline-none"
            />
            <span className="text-sm font-bold text-muted">GRAM</span>
          </div>
        </label>
        <p className="mt-1 text-xs text-subtle tabular">Доступно на карте: {fmt(ton)} GRAM</p>
        <label className="mt-4 block">
          <span className="mb-1.5 block text-[12px] font-semibold text-muted">Адрес кошелька</span>
          <input
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            placeholder="UQ... или myname.ton"
            className="w-full rounded-[12px] bg-white/5 px-4 py-3 text-sm outline-none ring-1 ring-white/10 focus:ring-accent"
          />
        </label>
        <label className="mt-4 block">
          <span className="mb-1.5 block text-[12px] font-semibold text-muted">Комментарий (необязательно)</span>
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="например, вывод на кошелёк"
            className="w-full rounded-[12px] bg-white/5 px-4 py-3 text-sm outline-none ring-1 ring-white/10 focus:ring-accent"
          />
        </label>
        <div className="mt-4 rounded-[12px] border border-line bg-white/[0.03] p-3 text-left text-[11.5px] leading-relaxed text-subtle">
          Адрес сохраняется на карте DeltaGram и виден владельцу. Можно указать UQ/EQ-адрес или свой .ton домен — владелец отправит GRAM туда.
        </div>
        <Button size="lg" className="mt-5 w-full" onClick={submit} disabled={loading || tooMuch || amount <= 0}>
          {loading ? 'Создаём заявку…' : tooMuch ? 'Недостаточно GRAM' : `Отправить ${fmt(amount)} GRAM`}
        </Button>
      </div>
    </Modal>
  )
}

function TONBuySheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6 text-center">
        <span className="mx-auto grid size-16 place-items-center rounded-full bg-[#0098EA]/15">
          <GramIcon className="size-9" />
        </span>
        <h3 className="mt-4 text-[22px] font-bold">Купить GRAM</h3>
        <p className="mt-1 text-sm text-muted">Фиксированный курс: <b className="text-fg">100 GRAM = 30 ₽</b></p>
        <div className="mt-5 rounded-[14px] border border-line bg-white/[0.03] p-4 text-left">
          <ol className="space-y-2.5 text-[13px] text-muted">
            <li className="flex gap-2.5"><b className="shrink-0 text-fg">1.</b><span>Откройте бота и нажмите «Купить GRAM».</span></li>
            <li className="flex gap-2.5"><b className="shrink-0 text-fg">2.</b><span>Бот попросит написать владельцу <b className="font-semibold text-fg">@linuxoidddd</b> и обсудить оплату.</span></li>
            <li className="flex gap-2.5"><b className="shrink-0 text-fg">3.</b><span>После оплаты нажмите «Написал» — владелец получит заявку.</span></li>
            <li className="flex gap-2.5"><b className="shrink-0 text-fg">4.</b><span>Владелец отправит GRAM на вашу карту, и баланс обновится.</span></li>
          </ol>
        </div>
        <a
          href="https://t.me/deltagram_casino_bot?start=ton"
          target="_blank"
          rel="noreferrer"
          className="mt-5 flex h-[52px] w-full items-center justify-center gap-2 rounded-[8px] bg-[#0098EA] text-[14px] font-semibold text-white transition-colors hover:bg-[#0086d3]"
        >
          <TelegramLogoIcon className="size-4" /> Открыть бота
        </a>
        <a
          href="https://t.me/linuxoidddd"
          target="_blank"
          rel="noreferrer"
          className="mt-2 flex h-11 w-full items-center justify-center gap-2 rounded-[8px] border border-line bg-white/[0.04] text-[13px] font-semibold text-fg"
        >
          Написать владельцу @linuxoidddd
        </a>
      </div>
    </Modal>
  )
}

function TONHistorySheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { tonHistory } = useStore()
  const [entries, setEntries] = useState<{ id: string; amount: number; kind: string; title: string; created: number }[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    tonHistory()
      .then((r) => setEntries(r.entries ?? []))
      .catch(() => setEntries([]))
      .finally(() => setLoading(false))
  }, [open, tonHistory])

  return (
    <Modal open={open} onClose={onClose}>
      <div className="px-6 pt-10 pb-6">
        <h3 className="text-center text-[22px] font-bold">История GRAM</h3>
        <p className="mt-1 text-center text-sm text-muted">Начисления, выводы и отправки с карты</p>
        <div className="mt-5 divide-y divide-white/5">
          {loading ? (
            <p className="py-10 text-center text-sm text-muted">Загружаем…</p>
          ) : entries.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted">Пока пусто. Награды появятся здесь.</p>
          ) : (
            entries.map((e) => (
              <div key={e.id} className="flex items-center gap-3 py-3">
                <span className={clsx('grid size-9 shrink-0 place-items-center rounded-full', e.amount > 0 ? 'bg-success/10 text-success' : 'bg-white/6 text-muted')}>
                  {e.amount > 0 ? <ArrowDownLeftIcon className="size-4" /> : <ArrowUpRightIcon className="size-4" />}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13.5px] font-medium">{e.title || 'Операция'}</p>
                  <p className="text-[11px] text-subtle">{fmtAgo(e.created)}</p>
                </div>
                <span className={clsx('shrink-0 text-[13px] font-semibold tabular', e.amount > 0 ? 'text-success' : 'text-fg')}>
                  {e.amount > 0 ? '+' : '−'}{fmt(Math.abs(e.amount))} GRAM
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </Modal>
  )
}
