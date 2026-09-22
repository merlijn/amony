import {BrowserRouter, Navigate, Route, Routes} from 'react-router-dom';
import React, {Suspense, use, useMemo} from 'react';
import {AxiosError} from 'axios';
import {getSession} from "./api/generated";
import {SessionContext} from "./api/Constants";
import {SessionInfo} from "./api/Model";
import LoginPage from "./pages/LoginPage";
import UsersPage from "./pages/UsersPage";
import AdminLayout from "./components/AdminLayout";

function App() {
  const sessionPromise = useMemo(() =>
      getSession()
        .then((authToken) => ({
          isLoggedIn: () => authToken.userId !== "anonymous",
          isAdmin: () => authToken.roles.includes("admin")
        } as SessionInfo))
        .catch((error: AxiosError) => {
          if (error.response?.status === 401 || error.response?.status === 403) {
            // Login is required and the user is not authenticated.
            return null;
          }
          console.log("Error getting session", error);
          return null;
        }),
    []);

  return (
    <BrowserRouter>
      <Suspense fallback={<div className="message-page">Loading…</div>}>
        <SessionGate sessionPromise={sessionPromise} />
      </Suspense>
    </BrowserRouter>
  );
}

function SessionGate({ sessionPromise }: { sessionPromise: Promise<SessionInfo | null> }) {
  const session = use(sessionPromise);

  if (session === null || !session.isLoggedIn()) {
    return <LoginPage />;
  }

  if (!session.isAdmin()) {
    return (
      <div className="message-page">
        <h1>Access denied</h1>
        <p>Your account does not have administrator access.</p>
      </div>
    );
  }

  return (
    <SessionContext.Provider value={session}>
      <Routes>
        <Route element={<AdminLayout />}>
          <Route index element={<Navigate to="/users" replace />} />
          <Route path="users" element={<UsersPage />} />
        </Route>
      </Routes>
    </SessionContext.Provider>
  );
}

export default App;
