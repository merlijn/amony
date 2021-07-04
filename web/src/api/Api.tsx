
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
  return doGET("/api/videos")
}



export async function setThumbnail(id: String, timeStamp: number) {


}