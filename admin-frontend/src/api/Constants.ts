import React from "react";
import {SessionInfo} from "./Model";

const anonymousSession: SessionInfo = {
  isLoggedIn: () => false,
  isAdmin: () => false,
}

export const SessionContext = React.createContext<SessionInfo>(anonymousSession);

export const Constants = {
  anonymousSession,
  identityProvider: import.meta.env.VITE_IDENTITY_PROVIDER || "admin",
}
