import { Api } from '../../api/Api';
import Dialog from '../common/Dialog';
import './Login.scss';

const Profile = (props: {onLogout: () => void }) => {

  const doLogout = () => { 
    Api.logout().then(props.onLogout); 
  }

  return (
    <Dialog title="Profile">
      <button type="submit" value="submit" className="abs-bottom-right button-primary" tabIndex={1} onClick = {doLogout} >Logout</button>
    </Dialog>
  );
}

export default Profile