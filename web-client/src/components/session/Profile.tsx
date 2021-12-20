import Cookies from 'js-cookie';
import { Api } from '../../api/Api';
import './Login.scss';

const Profile = (props: {onLogout: () => void }) => {

  const doLogin = (e: React.FormEvent<HTMLFormElement>) => { 
    e.preventDefault();
    Api.logout().then(props.onLogout); 
  }

  return (
    <div className="modal-dialog">
      <h2>Profile</h2>
      <button type="submit" value="submit" className="button-primary" tabIndex={3}>Logout</button>
    </div>
  );
}

export default Profile