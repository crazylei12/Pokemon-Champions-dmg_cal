let mode: string = '';

export function setStage9VerificationMode(value: string): void {
  mode = value;
}

export function takeStage9VerificationMode(): string {
  const value = mode;
  mode = '';
  return value;
}
