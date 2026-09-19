import {authLogout} from "../api/generated";
import "./Dashboard.scss";

const Dashboard = () => {
  const logout = async () => {
    try {
      await authLogout();
    } finally {
      window.location.href = "/";
    }
  };

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>Amony Admin</h1>
        <button className="button-secondary" onClick={logout}>Sign out</button>
      </header>
      <main className="dashboard-content">
        <p>Nothing here yet.</p>
      </main>
    </div>
  );
};

export default Dashboard;
