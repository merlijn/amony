import {useEffect, useState} from "react";

export function buildUrl(path: string, urlParams: Map<string, string>) {

  let url = path;
  let paramPath = ""

  urlParams.forEach((val, key) => {
    paramPath += `&${key}=${val}`
  })

  const result = paramPath ? `${path}?${paramPath.substring(1)}` : path

  console.log(`url: ${result}`)

  return result
}

export function withFallback<T>(e: T | null, fallback: T) {
  return e ? e : fallback;
}

export function copyParams(params: URLSearchParams) {

  const copy = new Map<string, string>()
  params.forEach((val, key) => {
    copy.set(key, val)
  })

  return copy
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

// Define general type for useWindowSize hook, which includes width and height
interface Size {
  width: number;
  height: number;
}

// Hook, adapted from; https://usehooks.com/useWindowSize/
export function useWindowSize(predicate: (oldSize: Size, newSize: Size) => boolean): Size {

  // Initialize state with undefined width/height so server and client renders match
  // Learn more here: https://joshwcomeau.com/react/the-perils-of-rehydration/
  const [windowSize, setWindowSize] = useState<Size>({
    width: window.innerWidth,
    height: window.innerHeight,
  });

  useEffect(() => {
    // Handler to call on window resize
    function handleResize() {

      const newSize: Size = {
        width: window.innerWidth,
        height: window.innerHeight,
      }

      // Set window width/height to state
      if (predicate(windowSize, newSize))
        setWindowSize(newSize);
    }
    // Add event listener
    window.addEventListener("resize", handleResize);
    // Call handler right away so state gets updated with initial window size
    handleResize();
    // Remove event listener on cleanup
    return () => window.removeEventListener("resize", handleResize);
  }, []); // Empty array ensures that effect is only run on mount

  return windowSize;
}