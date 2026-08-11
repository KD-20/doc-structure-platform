const KEY = "docstructure_device_id";

/** Client-generated, persisted in localStorage — identifies this browser's anonymous trial uploads (X-Device-Id header) without any login. See backend PublicDemoService. */
export function getDeviceId(): string {
  let id = localStorage.getItem(KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(KEY, id);
  }
  return id;
}
