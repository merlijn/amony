import Dialog from '../common/Dialog';
import {authLogout} from "../../api/generated";

const Profile = (props: {onLogout: () => void }) => {

  const doLogout = () => { 
    authLogout().then(props.onLogout);
  }

  return (
    <Dialog title="Profile">
      <button type="submit" value="submit" className="abs-bottom-right button-primary" tabIndex={1} onClick = {doLogout} >Logout</button>
    </Dialog>
  );
}

export default Profile