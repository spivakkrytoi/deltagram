import { Loader2, ShieldCheck, Upload, X } from "lucide-react";
import { useState } from "react";
import { createPortal } from "react-dom";
import { APIError, api, errorMessage } from "../api";
import { Alert } from "../components/ui";
import { useI18n } from "../i18n";
import { DELTAGRAM_SCHEDULE_TIMEZONE, kyivLocalInputValue, kyivToUnix } from "../lib/format";
import type { CommandResult } from "../types";

type PackOutcome = { title: string; status: string; gift_id?: string; error?: string };

export function GiftPackModal({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const { t } = useI18n();
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState("");
  const [preview, setPreview] = useState<CommandResult | null>(null);
  const [result, setResult] = useState<CommandResult | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [scheduleMode, setScheduleMode] = useState<"none" | "once" | "daily">("none");
  const [unlockAt, setUnlockAt] = useState(() => kyivLocalInputValue(3600));
  const [dailyTime, setDailyTime] = useState("21:40");
  const [dailyDuration, setDailyDuration] = useState("120");
  const [packLimit, setPackLimit] = useState("0");
  const [packPerUser, setPackPerUser] = useState("0");

  // The schedule is authored in Kyiv time and sent as an absolute instant or a
  // daily window; the server and clients never depend on the browser's zone.
  function schedulePayload() {
    if (scheduleMode === "once") {
      const unlock = kyivToUnix(unlockAt);
      if (unlock <= Math.floor(Date.now() / 1000)) throw new Error(t("gifts.lifecycle.unlockRequired"));
      return { locked_until_date: unlock };
    }
    if (scheduleMode === "daily") {
      const match = /^(\d{2}):(\d{2})$/.exec(dailyTime);
      const minute = match ? Number(match[1]) * 60 + Number(match[2]) : -1;
      const duration = Number(dailyDuration);
      if (minute < 0 || minute > 1439) throw new Error(t("gifts.lifecycle.unlockRequired"));
      if (!(duration >= 1 && duration <= 1440)) throw new Error(t("gifts.lifecycle.roundDurationRequired"));
      return {
        purchase_window_enabled: true,
        purchase_window_start_minute: minute,
        purchase_window_duration_minutes: duration,
        purchase_window_timezone: DELTAGRAM_SCHEDULE_TIMEZONE
      };
    }
    return {};
  }

  function form(confirm: boolean) {
    const data = new FormData();
    if (file) data.append("file", file);
    data.append("metadata", JSON.stringify({
      reason,
      confirm,
      command_id: confirm ? preview?.command_id : undefined,
      availability_total: Number(packLimit) || 0,
      per_user_total: Number(packPerUser) || 0,
      ...schedulePayload()
    }));
    return data;
  }

  async function validate() {
    if (!file || !reason.trim()) return;
    setBusy(true); setError(""); setPreview(null); setResult(null);
    try { setPreview(await api.importGiftPack(form(false))); }
    catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); }
  }

  async function confirm() {
    if (!preview || !file) return;
    setBusy(true); setError("");
    try {
      setResult(await api.importGiftPack(form(true)));
      setPreview(null);
      onImported();
    } catch (err) {
      if (err instanceof APIError && err.result && typeof err.result === "object" && "details" in err.result) {
        setResult(err.result as CommandResult);
        setPreview(null);
      }
      setError(errorMessage(err)); onImported();
    }
    finally { setBusy(false); }
  }

  const shown = result ?? preview;
  const gifts = (shown?.details?.gifts ?? []) as PackOutcome[];
  return createPortal(
    <div className="modal-backdrop" role="presentation">
      <section className="modal command-modal gift-import-modal" role="dialog" aria-modal="true" aria-label={t("gifts.pack.title")}>
        <div className="modal-head">
          <div><div className="eyebrow">{t("gifts.importEyebrow")}</div><h2>{t("gifts.pack.title")}</h2></div>
          <button className="icon-btn" type="button" onClick={onClose} disabled={busy} aria-label={t("action.close")}><X size={15} /></button>
        </div>
        <div className="command-body gift-import-modal-body">
          <p>{t("gifts.pack.hint")}</p>
          <label><span>{t("gifts.pack.file")}</span><input type="file" accept=".zip,application/zip" onChange={(event) => { setFile(event.target.files?.[0] ?? null); setPreview(null); setResult(null); }} /></label>
          {file && <div className="muted">{file.name} · {(file.size / 1048576).toFixed(1)} MB</div>}
          <label className="gift-reason-field"><span>{t("gifts.reason")}</span><input value={reason} onChange={(event) => { setReason(event.target.value); setPreview(null); setResult(null); }} placeholder={t("gifts.reasonPlaceholder")} /></label>
          <section className="gift-lifecycle">
            <span className="gift-field-label">{t("gifts.pack.limits")}</span>
            <div className="gift-fields-grid">
              <label><span>{t("gifts.limit")}</span><input type="number" min="0" value={packLimit} onChange={(event) => { setPackLimit(event.target.value); setPreview(null); setResult(null); }} /></label>
              <label><span>{t("gifts.perUserLimit")}</span><input type="number" min="0" value={packPerUser} onChange={(event) => { setPackPerUser(event.target.value); setPreview(null); setResult(null); }} /></label>
            </div>
            <div className="gift-import-note"><span>{t("gifts.pack.limitsHint")}</span></div>
          </section>
          <section className="gift-lifecycle">
            <span className="gift-field-label">{t("gifts.pack.schedule")}</span>
            <div className="gift-source-tabs" role="group" aria-label={t("gifts.pack.schedule")}>
              {(["none", "once", "daily"] as const).map((mode) => (
                <button key={mode} className={`btn ${scheduleMode === mode ? "primary" : ""}`} type="button"
                  aria-pressed={scheduleMode === mode} onClick={() => { setScheduleMode(mode); setPreview(null); setResult(null); }}>
                  {t(`gifts.pack.schedule.${mode}`)}
                </button>
              ))}
            </div>
            {scheduleMode !== "none" && <div className="gift-import-note"><span>{t("gifts.pack.schedule.hint")}</span></div>}
            {scheduleMode === "once" && <div className="gift-fields-grid">
              <label><span>{t("gifts.drop.unlockAt")}</span><input type="datetime-local" value={unlockAt} onChange={(event) => { setUnlockAt(event.target.value); setPreview(null); setResult(null); }} /></label>
            </div>}
            {scheduleMode === "daily" && <div className="gift-fields-grid">
              <label><span>{t("gifts.pack.schedule.time")}</span><input type="time" value={dailyTime} onChange={(event) => { setDailyTime(event.target.value); setPreview(null); setResult(null); }} /></label>
              <label><span>{t("gifts.pack.schedule.duration")}</span><input type="number" min="1" max="1440" value={dailyDuration} onChange={(event) => { setDailyDuration(event.target.value); setPreview(null); setResult(null); }} /></label>
            </div>}
            {scheduleMode !== "none" && <div className="gift-import-note"><span>{t("gifts.drop.timezoneHint")}</span></div>}
          </section>
          {error && <Alert>{error}</Alert>}
          {shown && <div className="gift-validation">
            <strong>{result ? t("gifts.pack.result") : t("gifts.pack.preview")}: {String(shown.details?.pack_name ?? "")}</strong>
            <div className="mono">SHA-256: {String(shown.details?.content_sha256 ?? "")}</div>
            <ul>{gifts.map((gift, index) => <li key={`${gift.title}-${index}`}>
              <strong>{gift.title}</strong> — {t(`gifts.pack.${gift.status}`)} {gift.gift_id ? `#${gift.gift_id}` : ""}{gift.error ? `: ${gift.error}` : ""}
            </li>)}</ul>
          </div>}
        </div>
        <div className="modal-actions">
          <button className="btn" type="button" onClick={onClose} disabled={busy}>{t("common.close")}</button>
          <button className="btn" type="button" onClick={validate} disabled={busy || !file || !reason.trim()}>{busy ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}{t("gifts.validate")}</button>
          <button className="btn primary" type="button" onClick={confirm} disabled={busy || !preview}><Upload size={15} />{t("gifts.confirmImport")}</button>
        </div>
      </section>
    </div>, document.body
  );
}
