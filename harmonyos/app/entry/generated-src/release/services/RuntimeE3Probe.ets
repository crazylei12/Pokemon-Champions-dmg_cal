class RuntimeE3Probe {
  async waitForCalculation(): Promise<void> {}

  async waitForStartup(): Promise<void> {}

  async waitForUpdate(): Promise<void> {}
}

export const runtimeE3Probe: RuntimeE3Probe = new RuntimeE3Probe();
