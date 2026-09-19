import { useEffect, useState } from 'react';
import DialogWindow from '../components/common/DialogWindow';
import LoginDialog from '../components/dialogs/LoginDialog';
import { getOAuthProviders } from '../api/generated';
import { OAuthProviderDto } from '../api/generated/model';
import './LoginPage.scss';

const LoginPage = () => {
  const [providers, setProviders] = useState<OAuthProviderDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getOAuthProviders()
      .then((data) => {
        if (data.length === 1) {
          // Single provider: redirect immediately, no need to show a choice.
          window.location.href = data[0].loginUrl;
        } else if (data.length === 0) {
          setError('No login providers are configured');
        } else {
          setProviders(data);
        }
      })
      .catch(() => setError('Failed to load login options'));
  }, []);

  return (
    <div className="login-page">
      <div className="login-page-panel">
        {error && (
          <DialogWindow title="Login">
            <p className="login-error">{error}</p>
          </DialogWindow>
        )}
        {!error && !providers && (
          <DialogWindow title="Login">
            <p>Loading...</p>
          </DialogWindow>
        )}
        {providers && <LoginDialog onClose={() => {}} providers={providers} />}
      </div>
    </div>
  );
};

export default LoginPage;
