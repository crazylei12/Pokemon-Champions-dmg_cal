export interface BridgeInfo {
  api: number;
  name: string;
  native: boolean;
}

declare const pcbridge: {
  getBridgeInfo(): BridgeInfo;
};

export default pcbridge;
