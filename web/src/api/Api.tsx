import {buildUrl} from "./Util";

const headers = { 'Content-type': 'application/json; charset=UTF-8' };

export async function doGET(path: string) {
  const headers = { 'Content-type': 'application/json; charset=UTF-8' };

  const response = await fetch(path, {
    method: 'GET',
    headers
  });

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}

export async function doPOST(path: string, postData: any) {

  const response = await fetch(path, {
    method: 'POST',
    body: JSON.stringify(postData),
    headers
  });

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}

export async function getVideos() {
  return doGET("/api/media")
}

export async function getMediaById(id: string) {
  return doGET(`/api/media/${id}`)
}

export async function createThumbnailAt(id: string, timestamp: number) {
  return doPOST(`/api/thumbnail/${id}`, timestamp)
}

export async function getTags() {
  return doGET('/api/tags')
}

export async function setThumbnail(id: String, timeStamp: number) {


}