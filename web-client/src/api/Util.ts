import {CSSProperties, useEffect, useRef, useState} from "react";
import {useCookies} from "react-cookie";
import {Constants} from "./Constants";

export function buildUrl(path: string, urlParams: Map<string, string> | undefined) {

  let paramPath = ""

  if (urlParams)
    urlParams.forEach((val, key) => {
      paramPath += `&${key}=${val}`
    })

  const result = paramPath ? `${path}?${paramPath.substring(1)}` : path

  return result
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
    if (minutes < 10)
      durationStr += '0'
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
  }, [predicate]); // Empty array ensures that effect is only run on mount

  return windowSize;
}

export function BoundedRatioBox(maxWidth: string, maxHeight: string, ratio: number): CSSProperties {
  return {
    width: `min(${maxWidth}, ${maxHeight} * ${ratio})`,
    height: `min(${maxHeight}, ${maxWidth} * 1 / ${ratio})`
  }
}

export const calculateColumns = () => {
  const c = Math.max(1, Math.round(window.innerWidth / Constants.gridSize));

  // console.log(`calculated columns: ${c}`)

  return c;
}


export const usePrevious = <T>(value: T): T | undefined => {
  const ref = useRef<T>();
  useEffect(() => {
    ref.current = value;
  });
  return ref.current;
};

export function useCookiePrefs<T>(key: string, path: string, defaultPreferences: T): [T, ((e: T) => void)] {

  const [cookiePreferences, setCookiePrefs] = useCookies([key])

  const setPrefsAndCookie = (s: T) => {
    setCookiePrefs(key, s)
  }

  if (cookiePreferences[key] === undefined) {
    console.log("setting defaults")
    setCookiePrefs(key, defaultPreferences, {path: path})
    return [defaultPreferences, setPrefsAndCookie];
  }
  else {
    return [cookiePreferences[key], setPrefsAndCookie]
  }
}