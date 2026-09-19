import {Constants} from "../api/Constants";
import "./LoginPage.scss";

const LoginPage = () => {
  const login = () => {
    window.location.href = `/api/auth/login/${Constants.oauthProvider}`;
  };

  return (
    <div className="login-page">
      <div className="login-page-panel">
        <h1>Amony Admin</h1>
        <p>Sign in with your administrator account to continue.</p>
        <button className="button-primary" onClick={login}>
          Sign in
        </button>
      </div>
    </div>
  );
};

export default LoginPage;
