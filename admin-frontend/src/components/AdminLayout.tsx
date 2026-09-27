import * as Collapsible from '@radix-ui/react-collapsible';
import {FiChevronLeft, FiLogOut, FiUsers} from 'react-icons/fi';
import {NavLink, Outlet} from 'react-router-dom';
import {authLogout} from '../api/generated';
import './AdminLayout.scss';

const AdminLayout = () => {
  const logout = async () => {
    // The backend clears the local session and, when the provider supports it, returns a URL that
    // ends the upstream identity provider session too.
    const {logoutUrl} = await authLogout();
    window.location.href = logoutUrl ?? '/';
  };

  return (
    <div className="admin-layout">
      <Collapsible.Root className="admin-sidebar" defaultOpen>
        <div className="admin-sidebar-header">
          <span className="admin-sidebar-title">Amony Admin</span>
          <Collapsible.Trigger className="admin-sidebar-toggle" aria-label="Toggle sidebar">
            <FiChevronLeft aria-hidden />
          </Collapsible.Trigger>
        </div>
        <Collapsible.Content className="admin-sidebar-content">
          <nav className="admin-nav">
            <NavLink
              to="/users"
              className={({isActive}) => (isActive ? 'admin-nav-item admin-nav-item-active' : 'admin-nav-item')}
            >
              <FiUsers aria-hidden />
              <span>Users</span>
            </NavLink>
          </nav>
          <div className="admin-sidebar-footer">
            <button className="button-secondary admin-sidebar-signout" onClick={logout}>
              <FiLogOut aria-hidden />
              <span>Sign out</span>
            </button>
          </div>
        </Collapsible.Content>
      </Collapsible.Root>
      <main className="admin-content">
        <Outlet />
      </main>
    </div>
  );
};

export default AdminLayout;
