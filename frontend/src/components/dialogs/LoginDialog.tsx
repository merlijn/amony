import { useEffect, useState } from 'react';
import Dialog from '../common/Dialog';
import { getOAuthProviders } from '../../api/generated';
import { OAuthProviderDto } from '../../api/generated/model';
import './LoginDialog.scss';

type LoginDialogProps = {
  onClose: () => void;
  providers?: OAuthProviderDto[] | null;
};

const LoginDialog = (props: LoginDialogProps) => {
  const [providers, setProviders] = useState<OAuthProviderDto[]>(props.providers || []);
  const [loading, setLoading] = useState(!props.providers);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Only fetch if providers weren't passed in
    if (props.providers) {
      return;
    }
    
    getOAuthProviders()
      .then((data) => {
        setProviders(data);
        setLoading(false);
      })
      .catch((err) => {
        setError('Failed to load login options');
        setLoading(false);
      });
  }, [props.providers]);

  const handleProviderClick = (provider: OAuthProviderDto) => {
    window.location.href = provider.loginUrl;
  };

  const ProviderIcon = ({ providerName }: { providerName: string }) => {
    const [imgError, setImgError] = useState(false);
    const iconPath = `/icons/${providerName.toLowerCase()}.svg`;
    const fallbackPath = '/icons/keylock.svg';

    return (
      <img
        src={imgError ? fallbackPath : iconPath}
        alt={`${providerName} icon`}
        className="provider-icon"
        onError={() => setImgError(true)}
      />
    );
  };

  if (loading) {
    return (
      <Dialog title="Login">
        <div className="login-dialog-content">
          <p>Loading...</p>
        </div>
      </Dialog>
    );
  }

  if (error) {
    return (
      <Dialog title="Login">
        <div className="login-dialog-content">
          <p className="login-error">{error}</p>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title="Login">
      <div className="login-dialog-content">
        <div className="providers-list">
          {providers.map((provider) => (
            <button
              key={provider.name}
              className="provider-button"
              onClick={() => handleProviderClick(provider)}
            >
              <ProviderIcon providerName={provider.name} />
              <span>Login with {provider.name}</span>
            </button>
          ))}
        </div>
      </div>
    </Dialog>
  );
};

export default LoginDialog;
