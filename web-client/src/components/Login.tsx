import Cookies from 'js-cookie';
import { Api } from '../api/Api';
import './Login.scss';

const Login = (props: {onLogin: () => void }) => {

  console.log(Cookies.get("session"))

  const doLogin = (e: React.FormEvent<HTMLFormElement>) => { 
    e.preventDefault();
    Api.login("admin", "admin").then(props.onLogin); 
  }

  return (
    <div className="login-container">
      <h2 className="login-header">Sign in</h2>
      <form onSubmit={ doLogin } name="login">
        <div className="username-header">Username</div>
        <div className="username-input"><input key="login-username" type="text" tabIndex={1}></input></div>
        <div className="password-header">Password</div>
        <div className="password-input"><input key="login-password" type="password" tabIndex={2}></input></div>
        <div className="feedback-message"></div>
        <div className="login-button"><button type="submit" value="submit" className="login-button" tabIndex={3}>Login</button></div>
      </form>
    </div>
  );
}

export default Login