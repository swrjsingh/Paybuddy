import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  ArrowUpRight,
  ArrowLeft,
  ArrowRight,
  Check,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Copy,
  CreditCard,
  FileText,
  Layers3,
  LayoutDashboard,
  Loader2,
  LogOut,
  Plus,
  RefreshCw,
  ShieldCheck,
  Wallet,
  Webhook,
  X,
  XCircle,
  Zap,
} from "lucide-react";
import QRCode from "qrcode";
import "./styles.css";

type Session = {
  token: string;
  email: string;
  merchantId: string;
  role: string;
  displayName: string;
  expiresAt: string;
};
type Order = {
  id: string;
  description: string;
  amountMinor: number;
  currency: string;
  status: string;
  createdAt: string;
  paymentIntentId: string | null;
};
type Intent = {
  id: string;
  orderId: string;
  amountMinor: number;
  currency: string;
  method: string;
  status: string;
  reviewStatus: string;
  providerId: string | null;
  providerTransactionId: string | null;
  checkoutToken: string | null;
  routingReason: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
};
type Entry = {
  id: string;
  paymentIntentId: string;
  orderId: string;
  providerId: string;
  providerTransactionId: string;
  amountMinor: number;
  entryKind: string;
  createdAt: string;
};
type Provider = {
  id: string;
  name: string;
  method: string;
  priority: number;
  enabled: boolean;
};
type History = { id: string; kind: string; message: string; createdAt: string };
type WebhookEntry = {
  providerId: string;
  eventId: string;
  intentId: string;
  deliveries: number;
  receivedAt: string;
};
const qc = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true } },
});
const readSession = (): Session | null => {
  try {
    return JSON.parse(sessionStorage.getItem("paybuddy-session") || "null");
  } catch {
    return null;
  }
};
const money = (value: number) =>
  new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" }).format(
    value / 100,
  );
const short = (id: string) => id.slice(0, 8).toUpperCase();
const time = (value: string) =>
  new Date(value).toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const session = readSession();
  const response = await fetch("/api" + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(session ? { Authorization: "Bearer " + session.token } : {}),
      ...options.headers,
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== "/auth/login") {
      sessionStorage.removeItem("paybuddy-session");
      window.dispatchEvent(new Event("session-expired"));
    }
    throw new Error(
      data.message || `Request could not be completed (${response.status}).`,
    );
  }
  return data as T;
}
const send = <T,>(
  path: string,
  body: unknown,
  method = "POST",
  headers: Record<string, string> = {},
) => api<T>(path, { method, body: JSON.stringify(body), headers });
function Badge({ status }: { status: string }) {
  let label = status.replaceAll("_", " ").toLowerCase();
  return (
    <span className={"badge " + status.toLowerCase()}>
      <span />
      {label}
    </span>
  );
}
function Brand() {
  return (
    <div className="brand">
      <span className="brand-icon">
        <Wallet size={21} />
      </span>
      <span>
        paybuddy<span className="brand-dot">.</span>
      </span>
    </div>
  );
}
function Empty({ title, description }: { title: string; description: string }) {
  return (
    <div className="empty">
      <Layers3 size={32} />
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  );
}
function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [email, setEmail] = useState("merchant@paybuddy.local");
  const [password, setPassword] = useState("PayBuddy123!");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function login(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onLogin(await send<Session>("/auth/login", { email, password }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login">
      <section className="login-main">
        <Brand />
        <div className="login-form">
          <div className="eyebrow">YOUR PAYMENT WORKSPACE</div>
          <h1>Welcome back.</h1>
          <p className="subtle">
            Your orders, payments, and their complete history.
          </p>
          <form onSubmit={login}>
            <label>
              Email address
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
              />
            </label>
            <label>
              Password
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </label>
            {error && (
              <div role="alert" className="error">
                {error}
              </div>
            )}
            <button className="primary full" disabled={busy}>
              {busy ? (
                <Loader2 className="spin" size={18} />
              ) : (
                <>
                  Sign in <ArrowRight size={18} />
                </>
              )}
            </button>
          </form>
          <div className="demo-accounts">
            <span>Try a demo account</span>
            <div>
              <button
                onClick={() => {
                  setEmail("merchant@paybuddy.local");
                  setPassword("PayBuddy123!");
                }}
              >
                Merchant
              </button>
              <button
                onClick={() => {
                  setEmail("ops@paybuddy.local");
                  setPassword("OpsBuddy123!");
                }}
              >
                Operations
              </button>
            </div>
          </div>
        </div>
        <div className="login-footer">
          <ShieldCheck size={15} /> Local payment sandbox · No real money
        </div>
      </section>
      <section className="login-art">
        <div className="art-grid" />
        <div className="art-content">
          <span className="art-pill">
            <Zap size={14} /> EVERY PAYMENT HAS A STORY
          </span>
          <h2>
            Follow the payment.
            <br />
            Keep the confidence.
          </h2>
          <p>
            A clear view from checkout to confirmation,
            <br />
            even when the unexpected happens.
          </p>
          <div className="flow-preview">
            <div>
              <span className="flow-icon">
                <FileText size={20} />
              </span>
              <span>
                Order created<small>The purchase starts here</small>
              </span>
              <CheckCircle2 size={18} />
            </div>
            <div>
              <span className="flow-icon">
                <CreditCard size={20} />
              </span>
              <span>
                Payment confirmed<small>A verified provider outcome</small>
              </span>
              <CheckCircle2 size={18} />
            </div>
            <div>
              <span className="flow-icon">
                <Layers3 size={20} />
              </span>
              <span>
                Recorded in the ledger<small>A lasting audit trail</small>
              </span>
              <CheckCircle2 size={18} />
            </div>
          </div>
          <div className="art-note">Simulated payments. Real traceability.</div>
        </div>
      </section>
    </div>
  );
}
function App() {
  const [session, setSession] = useState<Session | null>(readSession);
  const [page, setPage] = useState("overview");
  const [create, setCreate] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [toast, setToast] = useState("");
  const [busyOrder, setBusyOrder] = useState<string | null>(null);
  const [method, setMethod] = useState("UPI");
  const [search, setSearch] = useState("");
  const simulatorToken = window.location.hash.startsWith("#/simulator/")
    ? window.location.hash.slice(12)
    : null;
  useEffect(() => {
    const fn = () => {
      setSession(null);
      qc.clear();
    };
    window.addEventListener("session-expired", fn);
    return () => window.removeEventListener("session-expired", fn);
  }, []);
  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(""), 5000);
    return () => clearTimeout(id);
  }, [toast]);
  const ordersQ = useQuery({
    queryKey: ["orders"],
    queryFn: () => api<Order[]>("/orders"),
    enabled: !!session && !simulatorToken,
    refetchInterval: 2500,
  });
  const intentsQ = useQuery({
    queryKey: ["intents"],
    queryFn: () => api<Intent[]>("/payment-intents"),
    enabled: !!session && !simulatorToken,
    refetchInterval: 2500,
  });
  const ledgerQ = useQuery({
    queryKey: ["ledger"],
    queryFn: () => api<Entry[]>("/ledger"),
    enabled: !!session && !simulatorToken,
    refetchInterval: 2500,
  });
  const providersQ = useQuery({
    queryKey: ["providers"],
    queryFn: () => api<Provider[]>("/providers"),
    enabled: !!session && !simulatorToken,
  });
  const orders = ordersQ.data || [],
    intents = intentsQ.data || [],
    ledger = ledgerQ.data || [];
  function notify(s: string) {
    setToast(s);
  }
  function loggedIn(s: Session) {
    sessionStorage.setItem("paybuddy-session", JSON.stringify(s));
    setSession(s);
    setPage("overview");
    qc.clear();
  }
  function logout() {
    sessionStorage.removeItem("paybuddy-session");
    setSession(null);
    setSelected(null);
    setSearch("");
    qc.clear();
  }
  async function pay(order: Order) {
    const existing = intents.find((i) => i.orderId === order.id);
    if (existing) {
      setSelected(existing.id);
      return;
    }
    setBusyOrder(order.id);
    try {
      const storageKey = "paybuddy-key-" + order.id;
      let key = sessionStorage.getItem(storageKey);
      if (!key) {
        key = crypto.randomUUID();
        sessionStorage.setItem(storageKey, key);
      }
      const intent = await send<Intent>(
        "/payment-intents",
        { orderId: order.id, method },
        "POST",
        { "Idempotency-Key": key },
      );
      setSelected(intent.id);
      await qc.invalidateQueries({ queryKey: ["intents"] });
      notify("Payment intent created. Preparing your checkout.");
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setBusyOrder(null);
    }
  }
  if (simulatorToken) return <Simulator token={simulatorToken} />;
  if (!session) return <Login onLogin={loggedIn} />;
  const ops = session.role === "OPERATIONS";
  const nav = [
    { id: "overview", label: "Overview", icon: LayoutDashboard },
    { id: "orders", label: "Orders", icon: FileText },
    { id: "payments", label: "Payments", icon: CreditCard },
    { id: "ledger", label: "Ledger", icon: Layers3 },
    { id: "providers", label: "Providers", icon: Zap },
    ...(ops ? [{ id: "webhooks", label: "Webhooks", icon: Webhook }] : []),
  ];
  const active = nav.find((n) => n.id === page)!;
  const succeeded = intents.filter((i) => i.status === "SUCCEEDED"),
    pending = intents.filter((i) =>
      ["CREATED", "PROCESSING"].includes(i.status),
    );
  const error = ordersQ.error || intentsQ.error || ledgerQ.error;
  const matchingOrders = orders.filter((o) =>
    (o.description + " " + o.id + " " + o.status)
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  const matchingIntents = intents.filter((i) =>
    (i.id + " " + i.orderId + " " + i.providerId + " " + i.status)
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  return (
    <div className="workspace">
      <aside className="sidebar">
        <Brand />
        <div className="workspace-label">
          {ops ? "OPERATIONS WORKSPACE" : "MERCHANT WORKSPACE"}
        </div>
        <nav>
          {nav.map((n) => (
            <button
              key={n.id}
              onClick={() => {
                setPage(n.id);
                setSearch("");
              }}
              className={page === n.id ? "active" : ""}
            >
              <n.icon size={18} />
              {n.label}
              {page === n.id && <span className="nav-active-dot" />}
            </button>
          ))}
        </nav>
        <div className="sandbox-note">
          <ShieldCheck size={20} />
          <strong>Simulation mode</strong>
          <p>Explore the full payment flow. No real money is transferred.</p>
        </div>
        <div className="user-card">
          <div className="avatar">{ops ? "OP" : "AS"}</div>
          <div>
            <strong>{session.displayName}</strong>
            <small>{ops ? "Operations" : "Merchant"}</small>
          </div>
          <button aria-label="Sign out" title="Sign out" onClick={logout}>
            <LogOut size={17} />
          </button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div>
            Workspace <ChevronRight size={14} />
            <strong>{active.label}</strong>
          </div>
          <div className="top-right">
            <span className="live-dot" /> Live updates{" "}
            <span className="environment">LOCAL SANDBOX</span>
            <button
              className="mobile-signout icon-button"
              aria-label="Sign out"
              onClick={logout}
            >
              <LogOut size={15} />
            </button>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                {ops ? "PAYMENT OPERATIONS" : "YOUR BUSINESS, IN VIEW"}
              </div>
              <h1>{page === "overview" ? "Payment overview" : active.label}</h1>
              <p>
                {
                  (
                    {
                      overview:
                        "Follow your recent payments, from the first click to the final record.",
                      orders: "Every purchase and its current payment status.",
                      payments:
                        "Track payment attempts and inspect their complete history.",
                      ledger: "Append-only records of confirmed payments.",
                      providers:
                        "See where payments go and manage provider preferences.",
                      webhooks:
                        "Verified provider messages, including repeated deliveries.",
                    } as Record<string, string>
                  )[page]
                }
              </p>
            </div>
            <button className="primary" onClick={() => setCreate(true)}>
              <Plus size={18} /> Create order
            </button>
          </div>
          {error && (
            <div role="alert" className="error">
              {(error as Error).message}{" "}
              <button onClick={() => qc.invalidateQueries()}>Retry</button>
            </div>
          )}
          {page === "overview" && (
            <>
              <div className="stats">
                <Stat
                  label="Confirmed volume"
                  value={money(
                    succeeded.reduce((sum, i) => sum + i.amountMinor, 0),
                  )}
                  foot="From successful payments"
                  icon={<Wallet size={20} />}
                />
                <Stat
                  label="Successful payments"
                  value={String(succeeded.length)}
                  foot={`${orders.filter((o) => o.status === "PAID").length} orders confirmed paid`}
                  icon={<CheckCircle2 size={20} />}
                />
                <Stat
                  label="In progress"
                  value={String(pending.length)}
                  foot="Awaiting provider confirmation"
                  icon={<Clock3 size={20} />}
                />
                <Stat
                  label="Ledger records"
                  value={String(ledger.length)}
                  foot="One record per successful intent"
                  icon={<Layers3 size={20} />}
                />
              </div>
              <div className="overview-grid">
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Recent payments</h2>
                      <p>Latest activity in your workspace</p>
                    </div>
                    <button
                      className="text-button"
                      onClick={() => setPage("payments")}
                    >
                      View all <ArrowUpRight size={16} />
                    </button>
                  </div>
                  <PaymentTable
                    intents={intents.slice(0, 6)}
                    orders={orders}
                    open={setSelected}
                  />
                </section>
                <section className="panel payment-path">
                  <div className="panel-heading">
                    <div>
                      <h2>A clear payment path</h2>
                      <p>From purchase to permanent record</p>
                    </div>
                  </div>
                  {[
                    {
                      title: "Create an order",
                      text: "Set the description and amount.",
                      icon: FileText,
                    },
                    {
                      title: "Approve a simulated payment",
                      text: "Use the QR link or the approval screen.",
                      icon: CreditCard,
                    },
                    {
                      title: "Watch the confirmation",
                      text: "Follow the outcome and order update.",
                      icon: ShieldCheck,
                    },
                    {
                      title: "Inspect the ledger",
                      text: "Find the recorded payment effect.",
                      icon: Layers3,
                    },
                  ].map((x, i) => (
                    <div className="path-step" key={x.title}>
                      <span>
                        <x.icon size={18} />
                      </span>
                      <div>
                        <strong>{x.title}</strong>
                        <p>{x.text}</p>
                      </div>
                      <small>0{i + 1}</small>
                    </div>
                  ))}
                  <button
                    className="secondary full"
                    onClick={() => setCreate(true)}
                  >
                    Try a payment <ArrowRight size={16} />
                  </button>
                </section>
              </div>
              <p className="table-note">
                Overview covers the latest 200 records. Payment, order, and
                ledger updates may arrive a few seconds apart.
              </p>
            </>
          )}
          {page === "orders" && (
            <section className="panel">
              <div className="table-toolbar">
                <input
                  placeholder="Search orders…"
                  aria-label="Search orders"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
                <label className="inline-label">
                  Payment method
                  <select
                    aria-label="Payment method"
                    value={method}
                    onChange={(e) => setMethod(e.target.value)}
                  >
                    <option value="UPI">UPI simulator</option>
                    <option value="GENERIC">Card / wallet simulator</option>
                  </select>
                </label>
              </div>
              {matchingOrders.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Order</th>
                        <th>Amount</th>
                        <th>Status</th>
                        <th>Created</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {matchingOrders.map((o) => {
                        const intent = intents.find((i) => i.orderId === o.id);
                        return (
                          <tr key={o.id}>
                            <td>
                              <strong>{o.description}</strong>
                              <small className="mono">{short(o.id)}</small>
                            </td>
                            <td className="amount">{money(o.amountMinor)}</td>
                            <td>
                              <Badge status={o.status} />
                            </td>
                            <td className="subtle">{time(o.createdAt)}</td>
                            <td>
                              <button
                                className="secondary small"
                                disabled={busyOrder === o.id}
                                onClick={() => pay(o)}
                              >
                                {busyOrder === o.id ? (
                                  <Loader2 className="spin" size={14} />
                                ) : intent ? (
                                  "View payment"
                                ) : (
                                  "Pay order"
                                )}
                                <ArrowRight size={14} />
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="No orders here yet"
                  description="Create your first order to start a simulated payment."
                />
              )}
            </section>
          )}
          {page === "payments" && (
            <section className="panel">
              <div className="table-toolbar">
                <input
                  placeholder="Search payments…"
                  aria-label="Search payments"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
                <span className="subtle">
                  {matchingIntents.length} payments
                </span>
              </div>
              <PaymentTable
                intents={matchingIntents}
                orders={orders}
                open={setSelected}
              />
            </section>
          )}
          {page === "ledger" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Payment ledger</h2>
                  <p>
                    Confirmed payment effects are recorded once and cannot be
                    edited.
                  </p>
                </div>
                <span className="pill">
                  <ShieldCheck size={14} /> Append-only
                </span>
              </div>
              {ledger.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Payment</th>
                        <th>Provider</th>
                        <th>Entry</th>
                        <th>Amount</th>
                        <th>Recorded</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ledger.map((e) => (
                        <tr
                          key={e.id}
                          onClick={() => setSelected(e.paymentIntentId)}
                          className="clickable"
                        >
                          <td className="mono">{short(e.paymentIntentId)}</td>
                          <td>{e.providerId}</td>
                          <td>
                            <Badge status="SUCCEEDED" />
                          </td>
                          <td className="amount">{money(e.amountMinor)}</td>
                          <td className="subtle">{time(e.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="Your ledger starts with a payment"
                  description="Complete a simulated payment to see its permanent record."
                />
              )}
            </section>
          )}
          {page === "providers" && (
            <Providers
              providers={providersQ.data || []}
              operations={ops}
              notify={notify}
            />
          )}
          {page === "webhooks" && ops && <Webhooks />}
        </main>
        <footer className="app-footer">
          <span>PayBuddy · Simulated payment workspace</span>
          <span>
            <ShieldCheck size={13} /> Auditable from start to finish
          </span>
        </footer>
      </div>
      {create && (
        <CreateOrder
          close={() => setCreate(false)}
          created={async (order) => {
            setCreate(false);
            setPage("orders");
            await qc.invalidateQueries({ queryKey: ["orders"] });
            notify("Order created. Choose Pay order to start checkout.");
          }}
        />
      )}
      {selected && (
        <PaymentDetail
          id={selected}
          close={() => setSelected(null)}
          orders={orders}
          operations={ops}
          notify={notify}
        />
      )}{" "}
      {toast && (
        <div role="status" className="toast">
          <CheckCircle2 size={18} />
          {toast}
          <button
            aria-label="Dismiss notification"
            onClick={() => setToast("")}
          >
            <X size={15} />
          </button>
        </div>
      )}
    </div>
  );
}
function Stat({
  label,
  value,
  foot,
  icon,
}: {
  label: string;
  value: string;
  foot: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="stat">
      <div>
        <span>{label}</span>
        {icon}
      </div>
      <strong>{value}</strong>
      <small>{foot}</small>
    </div>
  );
}
function PaymentTable({
  intents,
  orders,
  open,
}: {
  intents: Intent[];
  orders: Order[];
  open: (id: string) => void;
}) {
  if (!intents.length)
    return (
      <Empty
        title="Your first payment is waiting"
        description="Create an order, then start a simulated checkout."
      />
    );
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Payment / order</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Provider</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {intents.map((i) => (
            <tr key={i.id} onClick={() => open(i.id)} className="clickable">
              <td>
                <strong>
                  {orders.find((o) => o.id === i.orderId)?.description ||
                    "Merchant order"}
                </strong>
                <small className="mono">{short(i.id)}</small>
              </td>
              <td className="amount">{money(i.amountMinor)}</td>
              <td>
                <Badge status={i.status} />
              </td>
              <td className="subtle">{i.providerId || "Selecting…"}</td>
              <td>
                <ChevronRight size={16} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function CreateOrder({
  close,
  created,
}: {
  close: () => void;
  created: (o: Order) => void;
}) {
  const [description, setDescription] = useState("");
  const [amount, setAmount] = useState("500");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      setError("Enter an amount with at most two decimal places.");
      return;
    }
    setBusy(true);
    try {
      created(
        await send<Order>("/orders", {
          description,
          amountMinor: Math.round(Number(amount) * 100),
          currency: "INR",
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="modal-backdrop" onClick={close}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-title"
        className="modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-heading">
          <div>
            <div className="eyebrow">NEW PURCHASE</div>
            <h2 id="create-title">Create an order</h2>
          </div>
          <button className="icon-button" aria-label="Close" onClick={close}>
            <X size={20} />
          </button>
        </div>
        <form onSubmit={submit}>
          <label>
            Order description
            <input
              autoFocus
              required
              maxLength={160}
              placeholder="e.g. Studio headphones"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </label>
          <label>
            Amount in INR
            <div className="currency-input">
              <span>₹</span>
              <input
                required
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                aria-label="Amount in INR"
              />
            </div>
          </label>
          <p className="form-note">
            This order will use simulated payments. Its amount is fixed after
            creation.
          </p>
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          <div className="modal-actions">
            <button type="button" className="secondary" onClick={close}>
              Cancel
            </button>
            <button className="primary" disabled={busy}>
              {busy ? (
                <Loader2 className="spin" size={17} />
              ) : (
                <>
                  Create order <ArrowRight size={16} />
                </>
              )}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
function PaymentDetail({
  id,
  close,
  orders,
  operations,
  notify,
}: {
  id: string;
  close: () => void;
  orders: Order[];
  operations: boolean;
  notify: (s: string) => void;
}) {
  const [qr, setQr] = useState("");
  const [acting, setActing] = useState(false);
  const client = useQueryClient();
  const q = useQuery({
    queryKey: ["intent", id],
    queryFn: () => api<Intent>("/payment-intents/" + id),
    refetchInterval: 1500,
  });
  const historyQ = useQuery({
    queryKey: ["history", id],
    queryFn: () => api<History[]>("/payment-intents/" + id + "/timeline"),
    refetchInterval: 2000,
  });
  const entriesQ = useQuery({
    queryKey: ["entries", id],
    queryFn: () => api<Entry[]>("/ledger/" + id),
    refetchInterval: 2000,
  });
  const i = q.data;
  const url = i?.checkoutToken
    ? `${location.origin}/#/simulator/${i.checkoutToken}`
    : "";
  useEffect(() => {
    if (url)
      QRCode.toDataURL(url, {
        width: 240,
        margin: 1,
        color: { dark: "#122c29", light: "#ffffff" },
      }).then(setQr);
  }, [url]);
  useEffect(() => {
    const fn = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", fn);
    return () => window.removeEventListener("keydown", fn);
  }, [close]);
  async function approve(status: string) {
    setActing(true);
    try {
      await send("/simulator/payments/" + i!.checkoutToken + "/complete", {
        status,
      });
      notify(
        "Simulator outcome saved. Waiting for the verified payment update.",
      );
      await client.invalidateQueries();
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setActing(false);
    }
  }
  async function retry() {
    try {
      const key =
        sessionStorage.getItem("paybuddy-key-" + i!.orderId) ||
        crypto.randomUUID();
      const result = await send<Intent>(
        "/payment-intents",
        { orderId: i!.orderId, method: i!.method },
        "POST",
        { "Idempotency-Key": key },
      );
      notify(
        result.id === id
          ? "Same payment intent returned. No duplicate payment created."
          : "Request completed.",
      );
    } catch (e) {
      notify((e as Error).message);
    }
  }
  async function replay() {
    try {
      await send("/operations/providers/payments/" + id + "/replay", {});
      notify("Three copies of the original callback queued.");
    } catch (e) {
      notify((e as Error).message);
    }
  }
  return (
    <div className="modal-backdrop detail-backdrop" onClick={close}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="payment-title"
        className="detail"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-heading">
          <div>
            <div className="eyebrow">PAYMENT TRACE</div>
            <h2 id="payment-title">
              {orders.find((o) => o.id === i?.orderId)?.description ||
                "Payment details"}
            </h2>
          </div>
          <button
            className="icon-button"
            aria-label="Close payment"
            onClick={close}
          >
            <X size={20} />
          </button>
        </div>
        {q.error ? (
          <div className="error">{(q.error as Error).message}</div>
        ) : !i ? (
          <div className="loading">
            <Loader2 className="spin" /> Loading payment…
          </div>
        ) : (
          <>
            <div className="detail-summary">
              <div>
                <span>Payment amount</span>
                <strong>{money(i.amountMinor)}</strong>
              </div>
              <Badge status={i.status} />
            </div>
            <div className="detail-facts">
              <div>
                <span>Payment ID</span>
                <button
                  className="copy-id"
                  onClick={() => {
                    navigator.clipboard.writeText(id);
                    notify("Payment ID copied.");
                  }}
                >
                  {short(id)} <Copy size={12} />
                </button>
              </div>
              <div>
                <span>Provider</span>
                <strong>{i.providerId || "Selecting…"}</strong>
              </div>
              <div>
                <span>Order</span>
                <Badge
                  status={
                    orders.find((o) => o.id === i.orderId)?.status || "UNPAID"
                  }
                />
              </div>
              <div>
                <span>Ledger</span>
                <strong>
                  {entriesQ.data?.length ? "Recorded" : "Not recorded yet"}
                </strong>
              </div>
            </div>
            {i.reviewStatus !== "NONE" && (
              <div className="error">
                This payment needs investigation. Its outcome has not been
                guessed.
              </div>
            )}
            {i.status === "SUCCEEDED" && (
              <div className="success-note">
                <CheckCircle2 size={22} />
                <div>
                  <strong>Payment confirmed</strong>
                  <p>
                    {orders.find((o) => o.id === i.orderId)?.status === "PAID"
                      ? "Your order is confirmed paid."
                      : "Your payment succeeded. Order confirmation is catching up."}
                  </p>
                </div>
              </div>
            )}
            {i.status === "FAILED" && (
              <div className="failure-note">
                <XCircle size={22} />
                <div>
                  <strong>Payment failed</strong>
                  <p>
                    The simulator confirmed failure. This order remains unpaid.
                  </p>
                </div>
              </div>
            )}
            {["CREATED", "PROCESSING"].includes(i.status) && (
              <div className="checkout">
                {i.checkoutToken ? (
                  <>
                    <div className="checkout-intro">
                      <span className="pill">
                        SIMULATED {i.method} CHECKOUT
                      </span>
                      <h3>Approve your payment</h3>
                      <p>
                        {i.method === "UPI"
                          ? "Scan this QR or open the approval screen."
                          : "Open the simulated card / wallet approval screen."}
                      </p>
                    </div>
                    {i.method === "UPI" && qr && (
                      <img
                        className="qr"
                        src={qr}
                        alt="QR code linking to the PayBuddy simulator"
                      />
                    )}
                    <div className="vpa">demo-merchant@paybuddy</div>
                    <a
                      className="secondary full"
                      target="_blank"
                      rel="noreferrer"
                      href={url}
                    >
                      Open simulator <ArrowUpRight size={16} />
                    </a>
                    <div className="simulation-actions">
                      <button
                        className="primary"
                        onClick={() => approve("SUCCEEDED")}
                        disabled={acting}
                      >
                        <Check size={16} /> Simulate success
                      </button>
                      <button
                        className="secondary"
                        onClick={() => approve("FAILED")}
                        disabled={acting}
                      >
                        Simulate failure
                      </button>
                    </div>
                    <p className="form-note">
                      No bank app or real money is involved.
                    </p>
                  </>
                ) : (
                  <div className="loading">
                    <Loader2 className="spin" />
                    <span>
                      Preparing checkout…
                      <small>
                        {i.lastError
                          ? "Recovering the same provider attempt."
                          : "Selecting your provider and saving the attempt."}
                      </small>
                    </span>
                  </div>
                )}
              </div>
            )}
            <div className="trace-heading">
              <h3>Payment timeline</h3>
              <span className="pill">
                <span className="live-dot" /> Updating
              </span>
            </div>
            <div className="timeline">
              {historyQ.data?.map((h) => (
                <div key={h.id} className="timeline-item">
                  <span className={"timeline-marker " + h.kind.toLowerCase()}>
                    {h.kind === "SUCCEEDED" ? <Check size={12} /> : <span />}
                  </span>
                  <div>
                    <strong>{h.kind.replaceAll("_", " ").toLowerCase()}</strong>
                    <p>{h.message}</p>
                    <small>{time(h.createdAt)}</small>
                  </div>
                </div>
              ))}
            </div>
            <div className="detail-actions">
              <button className="secondary small" onClick={retry}>
                <RefreshCw size={14} /> Repeat request safely
              </button>
              {operations && ["SUCCEEDED", "FAILED"].includes(i.status) && (
                <button className="secondary small" onClick={replay}>
                  <Webhook size={14} /> Replay callback ×3
                </button>
              )}
            </div>
          </>
        )}
      </section>
    </div>
  );
}
function Providers({
  providers,
  operations,
  notify,
}: {
  providers: Provider[];
  operations: boolean;
  notify: (s: string) => void;
}) {
  const client = useQueryClient();
  const scenariosQ = useQuery({
    queryKey: ["scenarios"],
    queryFn: () =>
      api<{ providerId: string; mode: string }[]>(
        "/operations/providers/scenarios",
      ),
    enabled: operations,
  });
  async function policy(p: Provider, enabled: boolean, priority = p.priority) {
    try {
      await send("/routing/policies/" + p.id, { enabled, priority }, "PUT");
      await client.invalidateQueries({ queryKey: ["providers"] });
      notify("Routing policy updated. Existing attempts keep their provider.");
    } catch (e) {
      notify((e as Error).message);
    }
  }
  async function scenario(provider: string, mode: string) {
    try {
      await send(
        "/operations/providers/" + provider + "/scenario",
        { mode },
        "PUT",
      );
      await client.invalidateQueries({ queryKey: ["scenarios"] });
      notify("Scenario applies to new payment attempts.");
    } catch (e) {
      notify((e as Error).message);
    }
  }
  return (
    <>
      <div className="provider-grid">
        {providers.map((p) => (
          <section className="panel provider-card" key={p.id}>
            <div className="provider-top">
              <span className="provider-icon">
                <Zap size={22} />
              </span>
              <Badge status={p.enabled ? "AVAILABLE" : "DISABLED"} />
            </div>
            <h2>{p.name}</h2>
            <p>
              {p.method === "UPI"
                ? "Simulated QR and VPA payments"
                : "Simulated card and wallet payments"}
            </p>
            <div className="provider-meta">
              <span>
                Method <strong>{p.method}</strong>
              </span>
              <span>
                Priority <strong>{p.priority}</strong>
              </span>
            </div>
            {operations && (
              <>
                <label className="toggle-row">
                  <span>Accept new attempts</span>
                  <input
                    type="checkbox"
                    checked={p.enabled}
                    onChange={(e) => policy(p, e.target.checked)}
                  />
                </label>
                <label>
                  Routing priority
                  <input
                    type="number"
                    min="1"
                    max="1000"
                    defaultValue={p.priority}
                    key={p.priority}
                    onBlur={(e) => {
                      const value = Number(e.target.value);
                      if (value !== p.priority) policy(p, p.enabled, value);
                    }}
                  />
                </label>
                <label>
                  New-attempt scenario
                  <select
                    aria-label={p.name + " scenario"}
                    value={
                      scenariosQ.data?.find((s) => s.providerId === p.id)
                        ?.mode || "NORMAL"
                    }
                    onChange={(e) => scenario(p.id, e.target.value)}
                  >
                    <option value="NORMAL">Normal approval</option>
                    <option value="DUPLICATE">Duplicate callback ×3</option>
                    <option value="DELAY">Delay callback 20 seconds</option>
                    <option value="TIMEOUT">Accept, then time out</option>
                    <option value="FAILURE">Confirmed failure</option>
                  </select>
                </label>
              </>
            )}
          </section>
        ))}
      </div>
      <div className="info-note">
        <ShieldCheck size={18} />
        <p>
          Providers are simulators. Lower priority numbers are selected first.
          An existing payment keeps its original provider, even if its policy
          changes.
        </p>
      </div>
    </>
  );
}
function Webhooks() {
  const q = useQuery({
    queryKey: ["webhooks"],
    queryFn: () => api<WebhookEntry[]>("/operations/webhooks"),
    refetchInterval: 2500,
  });
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Verified callback inbox</h2>
          <p>
            Repeated deliveries are counted without repeating the payment
            effect.
          </p>
        </div>
      </div>
      {q.error ? (
        <div className="error">{(q.error as Error).message}</div>
      ) : q.data?.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Event</th>
                <th>Payment</th>
                <th>Provider</th>
                <th>Deliveries</th>
                <th>Received</th>
              </tr>
            </thead>
            <tbody>
              {q.data.map((w) => (
                <tr key={w.eventId}>
                  <td className="mono">{short(w.eventId)}</td>
                  <td className="mono">{short(w.intentId)}</td>
                  <td>{w.providerId}</td>
                  <td>
                    <span className={w.deliveries > 1 ? "duplicate-count" : ""}>
                      {w.deliveries}
                    </span>
                  </td>
                  <td className="subtle">{time(w.receivedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty
          title="No verified callbacks yet"
          description="Approve a simulated payment to populate the inbox."
        />
      )}
    </section>
  );
}
function Simulator({ token }: { token: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const q = useQuery({
    queryKey: ["simulator", token],
    queryFn: () =>
      api<{
        amountMinor: number;
        status: string;
        providerId: string;
        method: string;
      }>("/simulator/payments/" + token + "/view"),
    refetchInterval: 2000,
  });
  async function complete(status: string) {
    setBusy(true);
    try {
      await send("/simulator/payments/" + token + "/complete", { status });
      await q.refetch();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="simulator-page">
      <Brand />
      <section className="simulator-card">
        <span className="pill">
          <ShieldCheck size={14} /> SIMULATION ONLY
        </span>
        {q.error ? (
          <div className="error">This checkout link is invalid or expired.</div>
        ) : !q.data ? (
          <div className="loading">
            <Loader2 className="spin" /> Loading checkout…
          </div>
        ) : (
          <>
            <div className="simulator-logo">
              <Wallet size={32} />
            </div>
            <h1>
              {q.data.status === "PROCESSING"
                ? "Approve payment"
                : q.data.status === "SUCCEEDED"
                  ? "Payment approved"
                  : "Payment declined"}
            </h1>
            <p>{q.data.providerId} · demo-merchant@paybuddy</p>
            <strong className="simulator-amount">
              {money(q.data.amountMinor)}
            </strong>
            {q.data.status === "PROCESSING" ? (
              <>
                <button
                  className="primary full"
                  disabled={busy}
                  onClick={() => complete("SUCCEEDED")}
                >
                  <CheckCircle2 size={18} /> Approve simulated payment
                </button>
                <button
                  className="secondary full"
                  disabled={busy}
                  onClick={() => complete("FAILED")}
                >
                  Decline payment
                </button>
              </>
            ) : (
              <div className="success-note">
                Return to your original checkout to see the order confirmation.
              </div>
            )}
            {error && <div className="error">{error}</div>}
            <p className="form-note">
              This screen does not access your bank or transfer money.
            </p>
          </>
        )}
        <a href="/" className="text-button">
          <ArrowLeft size={15} /> Return to PayBuddy
        </a>
      </section>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={qc}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
