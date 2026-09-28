import {AppConfigDto, ResourceDto} from "./generated";

/** URL of the original (full-size) resource content. */
export const resourceContentUrl = (resource: ResourceDto): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/content`;

const dimensionToken = (dimension: string): string => dimension === "height" ? "h" : "w";

/** URL of a derived thumbnail pinning a dimension ("width" | "height") and a resolution key (e.g. "s", "m"). */
export const resourceThumbnailUrl = (resource: ResourceDto, dimension: string, resolutionKey: string): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_${resource.thumbnailTimestamp ?? 0}_${dimensionToken(dimension)}_${resolutionKey}.webp`;

export type ThumbnailSources = {
  src: string;
  srcSet?: string;
  sizes?: string;
};

/**
 * Builds an `<img>` source set for a resource from the server-provided thumbnail sizes.
 *
 * Grid/list thumbnails are cropped to fill a fixed-aspect box with `object-fit: cover`, so the
 * image is scaled to the box width and the width is the operative dimension. The `w` descriptors
 * therefore equal the configured pixel sizes directly, and `sizes` is simply the box width.
 */
export const thumbnailSources = (
  resource: ResourceDto,
  config: AppConfigDto | undefined,
  boxWidthCss: number
): ThumbnailSources => {
  const defaultSelection = config?.defaultThumbnailResolution;
  const src = defaultSelection
    ? resourceThumbnailUrl(resource, defaultSelection.dimension, defaultSelection.key)
    : resourceThumbnailUrl(resource, "width", "s");

  const sizes = config?.thumbnailSizes;
  if (!sizes || sizes.length === 0)
    return { src };

  const srcSet = [...sizes]
    .sort((a, b) => a.pixels - b.pixels)
    .map(({key, pixels}) => `${resourceThumbnailUrl(resource, "width", key)} ${pixels}w`)
    .join(", ");

  return { src, srcSet, sizes: `${Math.max(1, Math.round(boxWidthCss))}px` };
};
