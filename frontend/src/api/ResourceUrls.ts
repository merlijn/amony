import {AppConfigDto, ResourceDto} from "./generated";

/** URL of the original (full-size) resource content. */
export const resourceContentUrl = (resource: ResourceDto): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/content`;

const dimensionToken = (dimension: string): string => dimension === "height" ? "h" : "w";

/** URL of a derived thumbnail pinning a dimension ("width" | "height") and a size in pixels. */
export const resourceThumbnailUrl = (resource: ResourceDto, dimension: string, size: number): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_${resource.thumbnailTimestamp ?? 0}_${dimensionToken(dimension)}_${size}.webp`;

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
 * Chooses a thumbnail URL for a resource based on the CSS width of the box it fills and the
 * server-configured resolution-picking strategy.
 *
 * Selection happens here rather than through `<img srcset>` because the browser's native srcset
 * algorithm always rounds up; doing it explicitly lets the server trade quality for bandwidth.
 */
export const thumbnailUrl = (resource: ResourceDto, config: AppConfigDto | undefined, boxWidthCss: number): string => {
  const sizes       = config?.thumbnailSizes;
  const dimension   = config?.defaultThumbnailResolution?.dimension ?? "width";
  const defaultSize = config?.defaultThumbnailResolution?.pixels ?? 512;

  if (!sizes || sizes.length === 0)
    return resourceThumbnailUrl(resource, dimension, defaultSize);

  const devicePixelRatio = window.devicePixelRatio || 1;
  const needed           = Math.max(1, boxWidthCss * devicePixelRatio);
  const size             = pickThumbnailSize(sizes, needed, config?.resolutionPickingStrategy ?? "round-up");

  return resourceThumbnailUrl(resource, "width", size);
};
