let stage6VerificationMode: string = '';

export function setStage6VerificationMode(mode: string): void {
  stage6VerificationMode = mode;
}

export function takeStage6VerificationMode(): string {
  const mode = stage6VerificationMode;
  stage6VerificationMode = '';
  return mode;
}
