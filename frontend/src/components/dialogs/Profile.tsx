import DialogWindow from '../common/DialogWindow';
import {authLogout} from "../../api/generated";

const Profile = (props: {onLogout: () => void }) => {

  const doLogout = () => { 
    authLogout().then(props.onLogout);
  }

  return (
    <DialogWindow title="Profile">
      <button type="submit" value="submit" className="abs-bottom-right button-primary" tabIndex={1} onClick = {doLogout} >Logout</button>
    </DialogWindow>
  );
}

export default Profile