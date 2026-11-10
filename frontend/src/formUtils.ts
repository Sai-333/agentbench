/** Safely reset a form after async work; event.currentTarget is null post-await. */
export function resetForm(form: HTMLFormElement | null | undefined): void {
  form?.reset()
}
