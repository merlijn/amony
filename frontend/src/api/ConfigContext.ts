import React, {useContext} from "react";
import {AppConfigDto} from "./generated";

/** Server-provided client configuration (e.g. the available thumbnail resolutions). */
export const ConfigContext = React.createContext<AppConfigDto | undefined>(undefined);

export const useAppConfig = (): AppConfigDto | undefined => useContext(ConfigContext);
