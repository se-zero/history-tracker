import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  downgradePlan,
  fetchBillingSummary,
  fetchCheckoutAvailability,
  openBillingPortal,
  startCheckout,
} from "@/api/billing";
import { upgradePlan } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { queryKeys } from "./queryKeys";

export function useBillingSummary() {
  const { status } = useAuth();
  return useQuery({
    queryKey: queryKeys.billing(),
    queryFn: fetchBillingSummary,
    enabled: status === "authenticated",
  });
}

export function useCheckoutAvailability() {
  return useQuery({
    queryKey: queryKeys.billingAvailability(),
    queryFn: fetchCheckoutAvailability,
    staleTime: 60_000,
  });
}

export function useStartCheckout() {
  return useMutation({
    mutationFn: startCheckout,
  });
}

export function useOpenBillingPortal() {
  return useMutation({
    mutationFn: (target: "overview" | "cancel") => openBillingPortal(target),
  });
}

export function useDowngradeToFree() {
  const { refresh } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: downgradePlan,
    onSuccess: async () => {
      await refresh();
      await queryClient.invalidateQueries({ queryKey: queryKeys.billing() });
    },
  });
}

export function useUpgradePlan() {
  const { refresh } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => upgradePlan(code),
    onSuccess: async () => {
      await refresh();
      await queryClient.invalidateQueries({ queryKey: queryKeys.billing() });
    },
  });
}
