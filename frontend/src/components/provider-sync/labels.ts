import type { ProviderDescriptor, ProviderStatus } from "@/lib/types";

export function actionLabel(
  descriptor: ProviderDescriptor,
  status: ProviderStatus
): string {
  switch (status.nextAction) {
    case "configure":
      return `${descriptor.displayName} is not configured`;
    case "connect":
      return `${descriptor.displayName} needs OAuth`;
    case "reconnect":
      return `${descriptor.displayName} needs reconnection`;
    case "sync":
      return `${descriptor.displayName} is ready to sync`;
  }
}

export function actionDetail(
  descriptor: ProviderDescriptor,
  status: ProviderStatus
): string {
  switch (status.nextAction) {
    case "configure":
      return `Set the ${descriptor.displayName} credentials and token encryption key on the backend.`;
    case "connect":
      return descriptor.workflowEndpoints.oauthStart
        ? "Login before syncing this provider."
        : "Connect this provider before syncing.";
    case "reconnect":
      return descriptor.workflowEndpoints.oauthStart
        ? "Restart OAuth if provider access was revoked."
        : "Reconnect this provider before syncing.";
    case "sync":
      return status.accounts.length === 1
        ? `Connected as ${status.accounts[0].providerInstanceId}.`
        : `${status.accounts.length} connected accounts.`;
  }
}

export function primaryOAuthLabel(status: ProviderStatus): string {
  switch (status.nextAction) {
    case "connect":
      return "Connect";
    case "reconnect":
      return "Reconnect";
    default:
      return "Start OAuth";
  }
}

export function formatStatus(value: string): string {
  return value
    .split(/[_-]/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
