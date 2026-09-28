import React, {useContext} from "react";
import {AppConfigDto} from "./generated";

/**
 * Server-provided client configuration, plus `imageFormat`: the image format this browser will
 * actually be served (the first server-supported format it can decode).
 */
export type AppConfig = AppConfigDto & { imageFormat: string };

export const ConfigContext = React.createContext<AppConfig | undefined>(undefined);

export const useAppConfig = (): AppConfig | undefined => useContext(ConfigContext);
