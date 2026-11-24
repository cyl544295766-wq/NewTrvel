// Browser verification for the isolated local TR-10/TR-11 fixture.
// Run through ego-browser nodejs, passing this absolute module path.
const assert = (await import("node:assert/strict")).default;
const origin = "http://127.0.0.1:35001";
const task = await taskSpace(globalThis.travelTestSpaceId ?? "Travel OS TR-10 / TR-11 本地验收");
const page = task.page("p1");
console.log("TASK_SPACE", task.spaceId);

async function call(token, method, path, body) {
  const response = await fetch(origin + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(method + " " + path + " -> " + response.status + " " + JSON.stringify(data));
  return data;
}

async function register(tag) {
  const username = "t1011" + tag + Date.now().toString(36) + Math.floor(Math.random() * 1000);
  const data = await call(null, "POST", "/api/auth/register", { username, password: "LocalE2eTest123" });
  console.log("FIXTURE_USER", data.user.id);
  return data;
}

async function waitForText(selector, value) {
  await page.waitForFunction(({ selector, value }) => document.querySelector(selector)?.textContent?.includes(value),
    { selector, value }, { timeout: 12000 });
}

const owner = await register("o");
const guest = await register("g");
const ownerCall = (method, path, data) => call(owner.token, method, path, data);
await ownerCall("POST", "/api/friends", { username: guest.user.username });
await call(guest.token, "POST", "/api/friends/" + owner.user.id + "/accept");
const { trip } = await ownerCall("POST", "/api/trips", { title: "TR-10 / TR-11 页面验收", budget: "500" });
console.log("FIXTURE_TRIP", trip.id);
const path = "/api/trips/" + trip.id;
const ledger = path + "/expenses";
await ownerCall("POST", path + "/members", { username: guest.user.username, role: "member" });
const { booking } = await ownerCall("POST", path + "/bookings", {
  title: "美元住宿页面验收", type: "hotel", amount: "120", currency: "USD"
});
const { expense: cny } = await ownerCall("POST", ledger, {
  title: "人民币页面验收", amount: "60", currency: "CNY", shareUserIds: [owner.user.id, guest.user.id]
});
console.log("FIXTURE_EXPENSE", cny.id);
console.log("FIXTURE_BOOKING", booking.id);

await page.goto(origin + "/login/");
await page.evaluate(({ token, user }) => {
  localStorage.setItem("travelos.token", token);
  localStorage.setItem("travelos.user", JSON.stringify(user));
}, owner);
await page.goto(origin + "/bookings/?trip=" + trip.id);
const card = '[data-bk="' + booking.id + '"]';
await page.waitForSelector(card + ' [data-ledger="convert-expense"]');
await page.click(card + ' [data-ledger="convert-expense"]', { label: "Open booking payment dialog" });
await page.waitForSelector("#bookingLedgerDialog[open] #ledgerAmount");
await page.fill("#ledgerAmount", "100.03");
await page.selectOption("#ledgerPayer", guest.user.id);
await page.click("#submitBookingLedger", { label: "Confirm booking payment amount" });
await waitForText(card, "实付 100.03 USD");

await page.click(card + ' [data-ledger="refund"]', { label: "Open booking refund dialog" });
await page.waitForSelector("#bookingLedgerDialog[open] #ledgerAmount");
await page.fill("#ledgerAmount", "20.03");
await page.click("#submitBookingLedger", { label: "Record partial booking refund" });
await waitForText(card, "已退款 20.03 USD");
const recorded = (await ownerCall("GET", ledger)).expenses;
for (const entry of recorded.filter(item => item.bookingId === booking.id)) console.log("FIXTURE_EXPENSE", entry.id);
const paid = recorded.find(item => item.bookingId === booking.id && !item.adjustmentKind);
assert.equal(paid.payer.id, guest.user.id);
assert.equal(recorded.find(item => item.adjustmentKind === "refund").amount, "-20.03");
await page.click(card + ' a[href*="expense="]', { label: "Open linked expense ledger" });
await page.waitForURL("**/expenses/?trip=" + trip.id + "&expense=" + paid.id);
await page.waitForSelector('[data-eid="' + paid.id + '"] a[href*="booking="]');
await waitForText("#wForeignSpent", "USD 80");

await page.click("#tab-stl", { label: "Show currency settlement plans" });
for (const currency of ["CNY", "USD"]) {
  const button = '[data-confirm-currency="' + currency + '"]';
  await page.waitForSelector(button);
  await page.click(button, { label: "Confirm " + currency + " statement" });
  await page.acceptDialog();
  await waitForText("#stlHistory", currency);
}
const confirmed = (await ownerCall("GET", ledger + "/settlement")).history;
assert.equal(confirmed.length, 2);
assert.deepEqual(confirmed.map(x => x.snapshot.currency).sort(), ["CNY", "USD"]);
assert.equal(confirmed.find(x => x.snapshot.currency === "USD").snapshot.totalAmount, "80.00");
const historyText = await page.evaluate(() => document.querySelector("#stlHistory").textContent);
assert.match(historyText, /账本范围 \d{4}-\d{2}-\d{2} 至 \d{4}-\d{2}-\d{2}/);

const list = (await ownerCall("GET", path + "/members")).members;
await ownerCall("DELETE", path + "/members/" + list.find(member => member.userId === guest.user.id).id);
const { expense: extra } = await ownerCall("POST", ledger, {
  title: "补记美元费用", amount: "12.34", currency: "USD", shareUserIds: [owner.user.id]
});
console.log("FIXTURE_EXPENSE", extra.id);
// Adjust an existing payment after the original payer has left the trip.
const { expense: secondRefund } = await ownerCall("POST", path + "/bookings/" + booking.id + "/refund",
  { amount: "10", operationKey: "browser-second-refund" });
console.log("FIXTURE_EXPENSE", secondRefund.id);
await page.goto(origin + "/expenses/?trip=" + trip.id + "&tab=settlement");
await waitForText("#stlNets", "已离队");
await waitForText("#stlTransfers", "本期差额 USD 2.34");
const next = (await ownerCall("GET", ledger + "/settlement")).byCurrency.USD;
assert.equal(next.baseSettlementId, confirmed.find(x => x.snapshot.currency === "USD").id);
assert.equal(next.totalAmount, "2.34");
assert.equal(next.nets.find(row => row.userId === guest.user.id).formerMember, true);
console.log("ALL PASS · 预订入账、退款、两种币种确认、离队成员和补记差额均通过");
console.log(await page.snapshot({ scope: "full_page" }));
