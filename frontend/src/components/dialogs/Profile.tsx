import DialogWindow from '../common/DialogWindow';
import {authLogout} from '../../api/generated';

const Profile = () => {

  const doLogout = async () => {
    // The backend clears the local session and, when the provider supports it, returns a URL that
    // ends the upstream identity provider session too.
    const {logoutUrl} = await authLogout();
    if (logoutUrl) window.location.href = logoutUrl;
    else window.location.reload();
  }

  return (
    <DialogWindow title="Profile">
      <button type="submit" value="submit" className="abs-bottom-right button-primary" tabIndex={1} onClick = {doLogout} >Logout</button>
    </DialogWindow>
  );
}

export default Profile