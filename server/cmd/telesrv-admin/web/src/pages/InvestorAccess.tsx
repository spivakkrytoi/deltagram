import { useEffect, useState } from "react";
import { KeyRound, RefreshCw, ShieldCheck } from "lucide-react";
import { api, errorMessage } from "../api";
import { ActionButton } from "../components/ActionButton";
import { Alert, Badge, SectionHead } from "../components/ui";

type Investor = { user_id: string; login: string; name: string; active: boolean };

// Lives inside Admin Users: the server, not this page, enforces admins.manage.
// Logins are portal-only; they never become admin operators.
export function InvestorAccess() {
  const [rows, setRows] = useState<Investor[]>([]);
  const [id, setID] = useState("");
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<Investor | null>(null);

  async function load() {
    setBusy(true);
    try {
      const response = await api.investors();
      setRows(response.rows);
      setError("");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => { void load(); }, []);
  function choose(row: Investor) {
    setSelected(row);
    setID(row.user_id);
    setLogin(row.login);
    setEnabled(row.active);
    setPassword("");
  }
  function clear() {
    setSelected(null);
    setID("");
    setLogin("");
    setPassword("");
    setEnabled(true);
  }
  const valid = /^[1-9]\d*$/.test(id) && /^[a-zA-Z][a-zA-Z0-9_.-]{2,47}$/.test(login)
    && (selected ? password === "" || password.length >= 12 : password.length >= 12);

  return (
    <section className="investor-admin" aria-label="Доступ инвесторов">
      <SectionHead title="Доступ инвесторов" />
      <p className="muted">Выдавайте отдельные логины и пароли для <a href="https://dltagram.duckdns.org/investor/" target="_blank" rel="noreferrer">панели инвестора ↗</a>. Это не административные учётные записи. Смена пароля и отключение доступа завершат все сеансы инвестора.</p>
      {error && <Alert>{error}</Alert>}
      <div className="table-wrap">
        <table className="data-table">
          <thead><tr><th>ID DeltaGram</th><th>Логин</th><th>Имя</th><th>Доступ</th><th></th></tr></thead>
          <tbody>{rows.map((row) => <tr key={row.user_id}>
            <td className="mono">{row.user_id}</td><td className="mono">{row.login}</td><td>{row.name}</td>
            <td><Badge tone={row.active ? "good" : "danger"}>{row.active ? "Активен" : "Отключён"}</Badge></td>
            <td><button className="btn" type="button" onClick={() => choose(row)}>Управлять</button></td>
          </tr>)}</tbody>
        </table>
      </div>
      <button type="button" className="btn icon-text" disabled={busy} onClick={() => void load()}><RefreshCw size={15} /> Обновить список</button>
      <div className="investor-access-form">
        <h3>{selected ? `Доступ для ${selected.login}` : "Выдать доступ инвестору"}</h3>
        <div className="investor-fields">
          <label className="form-field"><span>ID аккаунта DeltaGram</span><input inputMode="numeric" value={id} onChange={event => setID(event.target.value)} disabled={!!selected} placeholder="Например, 1780243200" /></label>
          <label className="form-field"><span>Логин (3–48 латинских символов)</span><input autoCapitalize="none" autoComplete="off" spellCheck={false} value={login} onChange={event => setLogin(event.target.value)} placeholder="investor_name" /></label>
          <label className="form-field"><span>{selected ? "Новый пароль (необязательно)" : "Пароль (от 12 символов)"}</span><input type="password" autoComplete="new-password" value={password} onChange={event => setPassword(event.target.value)} placeholder={selected ? "Оставьте пустым, чтобы не менять" : "Выдайте инвестору лично"} /></label>
        </div>
        <label className="permission-item standalone"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /><span className="permission-copy"><strong>Доступ активен</strong><small>При отключении вход и текущие сеансы будут заблокированы.</small></span></label>
        <div className="toolbar">
          <ActionButton
            label={selected ? "Сохранить доступ инвестора" : "Выдать доступ"}
            path="/api/actions/set-investor-access"
            tone="primary"
            icon={selected ? <ShieldCheck size={15} /> : <KeyRound size={15} />}
            secretField="password"
            disabled={!valid}
            payload={() => ({ user_id: id, login, password, enabled })}
            onDone={() => { setPassword(""); void load(); }}
          />
          {selected && <button type="button" className="btn" onClick={clear}>Новый инвестор</button>}
        </div>
        <p className="muted">Пароль не хранится в открытом виде и не показывается повторно. Передайте его инвестору через защищённый канал — не через публичный чат.</p>
      </div>
    </section>
  );
}
