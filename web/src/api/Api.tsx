import { Movie } from './Model';
import { deserialize } from 'typescript-json-serializer';

async function sendRequest(path: string) {
  const headers = { 'Content-type': 'application/json; charset=UTF-8' };

  const response = await fetch(`/${path}`, {
    method: 'GET',
    headers
  });

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}