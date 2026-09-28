import {AppConfigDto, ResourceDto} from "./generated";

const defaultAspectRatio = 16 / 9;

/** URL of the original (full-size) resource content. */
export const resourceContentUrl = (resource: ResourceDto): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/content`;

/** URL of a derived thumbnail at a given resolution key (e.g. "s", "m"). */
export const resourceThumbnailUrl = (resource: ResourceDto, resolutionKey: string): string =>
  `/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_${resource.thumbnailTimestamp ?? 0}_${resolutionKey}.webp`;

/** The aspect ratio (width / height) of the resource's own media, with a sane fallback. */
export const sourceAspectRatio = (resource: ResourceDto): number => {
  const {width, height} = resource.contentMeta;
  return width > 0 && height > 0 ? width / height : defaultAspectRatio;
};

export type ThumbnailSources = {
  src: string;
  srcSet?: string;
  sizes?: string;
};

/**
 * Builds an `<img>` source set for a resource from the server-provided thumbnail resolutions.
 *
 * `boxWidthCss` is the CSS width of the slot the image fills. Because images are displayed with
 * `object-fit: cover`, a slot whose aspect ratio differs from the source needs a wider image; that is
 * reflected in `sizes` so the browser picks a large enough candidate.
 *
 * When no config is available, falls back to a single default-resolution URL.
 */
export const thumbnailSources = (
  resource: ResourceDto,
  config: AppConfigDto | undefined,
  boxWidthCss: number,
  boxAspectRatio: number = defaultAspectRatio
): ThumbnailSources => {
  const defaultKey = config?.defaultThumbnailResolution ?? "s";
  const src        = resourceThumbnailUrl(resource, defaultKey);
  const resolutions = config?.thumbnailResolutions;

  if (!resolutions || resolutions.length === 0)
    return { src };

  const aspectRatio = sourceAspectRatio(resource);

  // With object-fit: cover the image has to be at least this wide once scaled to fill the slot.
  const effectiveWidth = Math.max(1, Math.round(boxWidthCss * Math.max(1, aspectRatio / boxAspectRatio)));

  const srcSet = [...resolutions]
    .sort((a, b) => a.height - b.height)
    .map(({key, height}) => `${resourceThumbnailUrl(resource, key)} ${Math.round(height * aspectRatio)}w`)
    .join(", ");

  return { src, srcSet, sizes: `${effectiveWidth}px` };
};
