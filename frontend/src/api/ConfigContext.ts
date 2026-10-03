import React, {useContext} from "react";
import {AppConfigDto} from "./generated";

/**
 * Server-provided client configuration, plus `imageFormat`: the image format this browser will
 * actually be served (the first server-supported format it can decode).
 */
export type AppConfig = AppConfigDto & { imageFormat: string };

/**
 * Last-resort configuration used when the server config cannot be loaded. Mirrors the backend
 * defaults in resources.conf so thumbnail and clip URLs stay valid without any inline fallbacks.
 */
export const defaultAppConfig: AppConfig = {
  thumbnailSizes: [96, 192, 384, 768, 1536],
  supportedFormats: ["webp", "jpeg"],
  defaultThumbnailResolution: { dimension: "width", pixels: 384 },
  resolutionPickingStrategy: "round-down",
  imageFormat: "webp",
};

export const ConfigContext = React.createContext<AppConfig>(defaultAppConfig);

export const useAppConfig = (): AppConfig => useContext(ConfigContext);
