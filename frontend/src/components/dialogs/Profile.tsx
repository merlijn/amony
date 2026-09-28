import {useContext} from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import DialogWindow from '../common/DialogWindow';
import InfoTable, {InfoRow} from '../common/InfoTable';
import {authLogout} from '../../api/generated';
import {SessionContext} from '../../api/Constants';
import './ConfigMenu.scss';

const Profile = () => {

  const session = useContext(SessionContext);

  const doLogout = async () => {
    // The backend clears the local session and, when the provider supports it, returns a URL that
    // ends the upstream identity provider session too.
    const {logoutUrl} = await authLogout();
    if (logoutUrl) window.location.href = logoutUrl;
    else window.location.reload();
  }

  return (
    <DialogWindow>
      <Tabs.Root defaultValue="user">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger className="tab-trigger" value="user">User</Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content className="tab-content" value="user">
          <InfoTable>
            <InfoRow label="User ID">{session.userId}</InfoRow>
            <InfoRow label="Roles">{session.roles.length > 0 ? session.roles.join(", ") : "-"}</InfoRow>
          </InfoTable>
        </Tabs.Content>
      </Tabs.Root>

      <button type="submit" value="submit" className="abs-bottom-right button-primary" tabIndex={1} onClick={doLogout}>Logout</button>
    </DialogWindow>
  );
}

export default Profile
