import { Api } from '../api/Api';
import './Login.scss';

const Login = (props: {onLogin: () => void }) => {

  const doLogin = (e: React.FormEvent<HTMLFormElement>) => { 
    e.preventDefault();
    Api.login("admin", "admin").then(props.onLogin); 
  }

  return (
    <div className="login-container">
      <h2 className="login-header">Sign in</h2>
      <form onSubmit={ doLogin } name="login">
        <div>
          <div className="header">Username</div>
          <div><input key="login-username" type="text"></input></div>
        </div>
        <div>
          <div className="header">Password</div>
          <div><input key="login-password" type="password"></input></div>
        </div>
        <button type="submit" value="submit" className="login-button">Login</button>
      </form>
    </div>
  );
}

export default Login