import React from "react";
import {SessionInfo} from "./Model";

const anonymousSession: SessionInfo = {
  isLoggedIn: () => false,
  isAdmin: () => false,
}

export const SessionContext = React.createContext<SessionInfo>(anonymousSession);

export const Constants = {
  anonymousSession,
  oauthProvider: import.meta.env.VITE_OAUTH_PROVIDER || "admin",
}
