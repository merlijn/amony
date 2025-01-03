import axios, {AxiosError, AxiosRequestConfig} from "axios";

async function refreshToken() {
  const response = await axios.post('/api/auth/refresh');
  // Update token storage here if needed
  return response;
}

export const customAxiosInstance = <T>(
  config: AxiosRequestConfig,
  options?: AxiosRequestConfig
): Promise<T> => {
  const source = axios.CancelToken.source();

  const executeRequest = (
    config: AxiosRequestConfig,
    options?: AxiosRequestConfig,
    refreshTokenOn401: boolean = true
  ): Promise<T> => {
    // Update authorization header with current token
    const currentConfig = {
      ...config,
      // headers: {
      //   ...config.headers,
      //   Authorization: `Bearer ${getCurrentToken()}`, // Implement getCurrentToken()
      // },
    };

    const promise = axios({
      ...currentConfig,
      ...options,
      cancelToken: source.token,
    })
      .then(({ data }) => data)
      .catch(async (error: AxiosError) => {
        if (error.response?.status === 401 && refreshTokenOn401) {
          try {
            const refreshResponse = await refreshToken();
            if (refreshResponse.status === 200) {
              return executeRequest(config, options, false);
            }
          } catch (refreshError) {
            throw error;
          }
        }
        throw error;
      });

    // @ts-ignore
    promise.cancel = () => {
      source.cancel('Query was cancelled');
    };

    return promise;
  };

  return executeRequest(config, options);
};