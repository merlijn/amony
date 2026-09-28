/**
 * Detection of which image formats the current browser can actually decode.
 *
 * A decode probe is used rather than `canvas.toDataURL(mime)` because canvas encoding support does
 * not match decoding support: Chrome decodes AVIF but `canvas.toDataURL("image/avif")` returns PNG,
 * so a canvas check would wrongly skip AVIF.
 *
 * Each sample below is a tiny (2x2) image; if it loads, the browser decodes that format.
 */
const FORMAT_SAMPLES: Record<string, string> = {
  avif: "data:image/avif;base64,AAAAHGZ0eXBhdmlmAAAAAG1pZjFhdmlmbWlhZgAAANZtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAAAAAAAAAAAAAAAAAAACJpbG9jAAAAAERAAAEAAQAAAAAA+gABAAAAAAAAACgAAAAjaWluZgAAAAAAAQAAABVpbmZlAgAAAAABAABhdjAxAAAAAA5waXRtAAAAAAABAAAAVmlwcnAAAAA4aXBjbwAAAAxhdjFDgUBsAAAAABRpc3BlAAAAAAAAAAIAAAACAAAAEHBpeGkAAAAAAwwMDAAAABZpcG1hAAAAAAAAAAEAAQOBAgMAAAAwbWRhdBIACghYADa0BDQbhDIaGUeHhiGJpppmgAAAkD+bDGFLK02PUUVOpCA=",
  jxl:  "data:image/jxl;base64,/woIkAEAE4gCAMQAtZ8gAAAVKqOMG7yc6/nyQ4fFtI3rDG21bWEJY7O9MEhIOIONix6+OUjj0huQkEoSAA==",
  webp: "data:image/webp;base64,UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoCAAIAAgA0JaACdLoB+AADsAD+8MQL/yC5YXXI1/8gP+QH/ID/+PIAAAA=",
  jpeg: "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAACAAIDAREAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAVAQEBAAAAAAAAAAAAAAAAAAAHCf/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/ADoDFU3/2Q==",
  png:  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACAQMAAABIeJ9nAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAADUExURf8AABniCTcAAAAHdElNRQfqCRwLMhienTvUAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDI2LTA5LTI4VDExOjUwOjI0KzAwOjAwgKUZ+AAAACV0RVh0ZGF0ZTptb2RpZnkAMjAyNi0wOS0yOFQxMTo1MDoyNCswMDowMPH4oUQAAAAodEVYdGRhdGU6dGltZXN0YW1wADIwMjYtMDktMjhUMTE6NTA6MjQrMDA6MDCm7YCbAAAADElEQVQI12NgYGAAAAAEAAEnNCcKAAAAAElFTkSuQmCC",
};

const canDecode = (dataUrl: string): Promise<boolean> =>
  new Promise((resolve) => {
    const image = new Image();
    image.onload = () => resolve(image.width > 0);
    image.onerror = () => resolve(false);
    image.src = dataUrl;
  });

/**
 * Returns the first of `supportedFormats` (in the server's preference order) that this browser can
 * decode, falling back to the first entry when none of the probes succeed.
 */
export const pickSupportedFormat = async (supportedFormats: string[], fallback = "webp"): Promise<string> => {
  for (const format of supportedFormats) {
    const sample = FORMAT_SAMPLES[format];
    if (sample && await canDecode(sample))
      return format;
  }
  return supportedFormats[0] ?? fallback;
};
