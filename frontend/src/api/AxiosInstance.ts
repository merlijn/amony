import axios, {AxiosError, AxiosRequestConfig} from "axios";
import Cookies from "js-cookie";
import qs from 'qs'

async function refreshToken() {

  const response = await axios.post('/api/auth/refresh', undefined, { headers: xsrfTokenHeader() });

  return response;
}

function xsrfTokenHeader() {
  const xsrfTokenCookie = Cookies.get("XSRF-TOKEN");
  return xsrfTokenCookie ?  { 'X-XSRF-TOKEN': xsrfTokenCookie } : { };
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

    const configWithOverrides: AxiosRequestConfig = {
      ...config,
      paramsSerializer: (params: any) => qs.stringify(params, { arrayFormat: 'repeat' }), // this is to prevent array params from being serialized as "paramName[]=value"
      headers: {
        ...config.headers,
        ...xsrfTokenHeader(),
      },
    };

    const promise = axios({
      ...configWithOverrides,
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