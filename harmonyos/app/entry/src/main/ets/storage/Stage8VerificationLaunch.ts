let mode: string = '';

export function setStage8VerificationMode(value: string): void {
  mode = value;
}

export function takeStage8VerificationMode(): string {
  const value = mode;
  mode = '';
  return value;
}
