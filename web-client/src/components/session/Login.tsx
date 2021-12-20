import { useEffect, useRef, useState } from 'react';
import { Api } from '../../api/Api';
import './Login.scss';

const Login = (props: { onLoginSuccess: () => void }) => {

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const doLogin = (e: React.FormEvent<HTMLFormElement>) => { 
    e.preventDefault();
    Api.login(username, password).then(props.onLoginSuccess); 
  }

  return (
    <div className = "login-container">
      <h2 className = "login-header">Sign in</h2>
      <form onSubmit = { doLogin } name="login">
        <div key="username-header" className="username-header">Username</div>
        <div key="username-input" className="username-input">
          <input key="login-username" type="text" tabIndex={1} value={username} onChange = {(e) => setUsername(e.target.value) }/>
        </div>
        <div key="password-header" className="password-header" >Password</div>
        <div key="password-input" className="password-input">
          <input key="login-password" type="password" tabIndex={2} value={password} onChange = {(e) => setPassword(e.target.value) }></input>
        </div>
        <div key="feedback" className="feedback-message"></div>
        <div key="login-button" className="login-button"><button type="submit" value="submit" className="login-button" tabIndex={3}>Login</button></div>
      </form>
    </div>
  );
}

export default Login