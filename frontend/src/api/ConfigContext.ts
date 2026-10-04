import React, {useContext} from "react";
import {AppConfigDto} from "./generated";

/**
 * Server-provided client configuration, plus `imageFormat` and `videoFormat`: the formats this
 * browser will actually be served (the first server-supported format it can decode/play).
 */
export type AppConfig = AppConfigDto & { imageFormat: string; videoFormat: string };

/**
 * Last-resort configuration used when the server config cannot be loaded. Mirrors the backend
 * defaults in resources.conf so thumbnail and clip URLs stay valid without any inline fallbacks.
 */
export const defaultAppConfig: AppConfig = {
  thumbnailSizes: [64, 128, 256, 512, 768, 1024],
  supportedImageFormats: ["webp", "jpeg"],
  supportedVideoFormats: ["mp4"],
  defaultThumbnailResolution: { dimension: "width", pixels: 256 },
  resolutionPickingStrategy: "round-nearest",
  imageFormat: "webp",
  videoFormat: "mp4",
};

export const ConfigContext = React.createContext<AppConfig>(defaultAppConfig);

export const useAppConfig = (): AppConfig => useContext(ConfigContext);
