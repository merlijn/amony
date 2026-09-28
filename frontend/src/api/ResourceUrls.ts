import {AppConfigDto, ResourceDto, ThumbnailSizeDto} from "./generated";

/** URL of the original (full-size) resource content. */
export const resourceContentUrl = (resource: ResourceDto): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/content`;

const dimensionToken = (dimension: string): string => dimension === "height" ? "h" : "w";

/** URL of a derived thumbnail pinning a dimension ("width" | "height") and a resolution key (e.g. "s", "m"). */
export const resourceThumbnailUrl = (resource: ResourceDto, dimension: string, resolutionKey: string): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_${resource.thumbnailTimestamp ?? 0}_${dimensionToken(dimension)}_${resolutionKey}.webp`;

type PickingStrategy = "round-up" | "round-down" | "round-nearest";

/**
 * Picks the configured thumbnail size closest to `needed` (device pixels) according to the strategy.
 * Ties for "round-nearest" favour the smaller size.
 */
const pickThumbnailSize = (sizes: ThumbnailSizeDto[], needed: number, strategy: string): ThumbnailSizeDto => {
  const sorted = [...sizes].sort((a, b) => a.pixels - b.pixels);

  switch (strategy as PickingStrategy) {
    case "round-down":
      return [...sorted].reverse().find((size) => size.pixels <= needed) ?? sorted[0];
    case "round-nearest":
      return sorted.reduce((best, size) =>
        Math.abs(size.pixels - needed) < Math.abs(best.pixels - needed) ? size : best
      );
    default: // "round-up"
      return sorted.find((size) => size.pixels >= needed) ?? sorted[sorted.length - 1];
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
  const sizes      = config?.thumbnailSizes;
  const dimension  = config?.defaultThumbnailResolution?.dimension ?? "width";
  const defaultKey = config?.defaultThumbnailResolution?.key ?? "s";

  if (!sizes || sizes.length === 0)
    return resourceThumbnailUrl(resource, dimension, defaultKey);

  const devicePixelRatio = window.devicePixelRatio || 1;
  const needed           = Math.max(1, boxWidthCss * devicePixelRatio);
  const chosen           = pickThumbnailSize(sizes, needed, config?.resolutionPickingStrategy ?? "round-up");

  return resourceThumbnailUrl(resource, "width", chosen.key);
};
