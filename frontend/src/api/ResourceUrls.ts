import {AppConfig} from "./ConfigContext";
import {ClipDto, ResourceDto} from "./generated";

/** URL of the original (full-size) resource content. */
export const resourceContentUrl = (resource: ResourceDto): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/content`;

const dimensionToken = (dimension: string): string => dimension === "height" ? "h" : "w";

/** URL of a derived thumbnail pinning a dimension ("width" | "height"), a size in pixels and a format. */
export const resourceThumbnailUrl = (resource: ResourceDto, dimension: string, size: number, format: string): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_${resource.thumbnailTimestamp ?? 0}_${dimensionToken(dimension)}_${size}.${format}`;

type PickingStrategy = "round-up" | "round-down" | "round-nearest";

/**
 * Picks the configured size closest to `needed` (device pixels) according to the strategy.
 * Ties for "round-nearest" favour the smaller size.
 */
const pickThumbnailSize = (sizes: number[], needed: number, strategy: string): number => {
  const sorted = [...sizes].sort((a, b) => a - b);

  switch (strategy as PickingStrategy) {
    case "round-down":
      return [...sorted].reverse().find((size) => size <= needed) ?? sorted[0];
    case "round-nearest":
      return sorted.reduce((best, size) =>
        Math.abs(size - needed) < Math.abs(best - needed) ? size : best
      );
    default: // "round-up"
      return sorted.find((size) => size >= needed) ?? sorted[sorted.length - 1];
  }
};

/**
 * Picks the configured resolution (device pixels) for a box of the given CSS width, honouring the
 * server-configured picking strategy. Falls back to the default resolution when none are configured.
 */
const resolutionFor = (config: AppConfig | undefined, boxWidthCss: number): number => {
  const sizes       = config?.thumbnailSizes;
  const defaultSize = config?.defaultThumbnailResolution?.pixels ?? 512;

  if (!sizes || sizes.length === 0)
    return defaultSize;

  const devicePixelRatio = window.devicePixelRatio || 1;
  const needed           = Math.max(1, boxWidthCss * devicePixelRatio);

  return pickThumbnailSize(sizes, needed, config?.resolutionPickingStrategy ?? "round-up");
};

/** The dimension thumbnails (and clips) are pinned to; width by default, since the grid is width-driven. */
const operativeDimension = (config: AppConfig | undefined): string =>
  config?.defaultThumbnailResolution?.dimension ?? "width";

/**
 * Chooses a thumbnail URL for a resource based on the CSS width of the box it fills, the
 * server-configured resolution-picking strategy and the format this browser can decode.
 *
 * Size selection happens here rather than through `<img srcset>` because the browser's native srcset
 * algorithm always rounds up; doing it explicitly lets the server trade quality for bandwidth.
 */
export const thumbnailUrl = (resource: ResourceDto, config: AppConfig | undefined, boxWidthCss: number): string => {
  const format = config?.imageFormat ?? config?.supportedFormats?.[0] ?? "webp";

  return resourceThumbnailUrl(resource, operativeDimension(config), resolutionFor(config, boxWidthCss), format);
};

/**
 * URL of a hover-preview clip, built from the clip's timestamp and pinned to the same operative
 * dimension and resolution ladder as thumbnails, so a preview is sized for the box it fills rather
 * than always at a fixed height.
 */
export const clipUrl = (clip: ClipDto, config: AppConfig | undefined, boxWidthCss: number): string => {
  const dimension = dimensionToken(operativeDimension(config));
  const size      = resolutionFor(config, boxWidthCss);

  return `/api/resources/${clip.bucketId}/${clip.resourceId}/clip_${clip.start}_${dimension}_${size}.mp4`;
};
