let stage7VerificationMode: string = '';

export function setStage7VerificationMode(mode: string): void {
  stage7VerificationMode = mode;
}

export function takeStage7VerificationMode(): string {
  const result = stage7VerificationMode;
  stage7VerificationMode = '';
  return result;
}
