import { api } from "./client";

export interface BillingSubscriptionView {
  status: string;
  currentPeriodEndsAt: string | null;
  cancelScheduledAt: string | null;
}

export interface BillingSummary {
  planSource: "SUBSCRIPTION" | "CODE" | "NONE";
  subscription: BillingSubscriptionView | null;
  lastSubscriptionEndedAt: string | null;
  checkoutAvailable: boolean;
}

export interface CheckoutSession {
  transactionId: string;
  clientToken: string;
  environment: string;
}

export interface BillingPortal {
  url: string;
}

export interface CheckoutAvailability {
  checkoutAvailable: boolean;
}

export async function fetchBillingSummary(): Promise<BillingSummary> {
  const { data } = await api.get<BillingSummary>("/me/billing");
  return data;
}

export async function startCheckout(): Promise<CheckoutSession> {
  const { data } = await api.post<CheckoutSession>("/me/billing/checkout");
  return data;
}

export async function openBillingPortal(target: "overview" | "cancel"): Promise<BillingPortal> {
  const { data } = await api.post<BillingPortal>("/me/billing/portal", { target });
  return data;
}

export async function downgradePlan(): Promise<void> {
  await api.post("/me/plan/downgrade");
}

export async function fetchCheckoutAvailability(): Promise<CheckoutAvailability> {
  const { data } = await api.get<CheckoutAvailability>("/billing/availability");
  return data;
}
