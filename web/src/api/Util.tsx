
export function buildUrl(path: string, params: Map<string, string>) {

  let url = path;
  let paramPath = ""

  params.forEach((val, key) => {
    paramPath += `&${key}=${val}`
  })

  const result = paramPath ? `${path}?${paramPath.substring(1)}` : path

  console.log(`url: ${result}`)

  return result
}

export function durationInMillisToString (duration: number) {

  const secondsInMillis = 1000;
  const minutesInMilis = 1000 * 60;
  const hoursInMillis = minutesInMilis * 60;

  const hours = Math.trunc(duration / hoursInMillis)
  const minutes = Math.trunc(duration % hoursInMillis / minutesInMilis)
  const seconds = Math.trunc(duration % minutesInMilis / secondsInMillis)

  let durationStr = ""

  if (hours > 0) {
    durationStr += `${hours}:`
  }

  durationStr += `${minutes}:`

  if (seconds < 10)
    durationStr += '0'

  durationStr += `${seconds}`

  return durationStr
}