import React, {MutableRefObject, useEffect, useRef, useState} from "react";
import {useCookies} from "react-cookie";
import _ from "lodash";
import {useLocation, useNavigate} from "react-router-dom";
import {buildUrl, copyParams} from "./Util";

export const useStateRef = <T>(value: T): [T, MutableRefObject<T>, ((e: T) => void)] => {
  const [getState, _setState] = useState<T>(value)
  const stateRef = React.useRef(getState);
  const setState = (v: T) => {
    stateRef.current = v;
    _setState(v);
  };

  return [getState, stateRef, setState]
}

export const useUrlParam = (name: string, defaultValue: string): [string, (v: string ) => any] => {
  const location = useLocation();
  const navigate = useNavigate();
  const [param, setParam] = useStateNeq<string>(defaultValue);

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    setParam(params.get(name) || defaultValue)
  }, [location])

  const updateParam = (value: string) => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)
    
    if (value === defaultValue)
      newParams.delete(name)
    else  
      newParams.set(name, value)

    const url = buildUrl(location.pathname, newParams)
    navigate(url)
  }

  return [param, updateParam];
}

export const useListener = <K extends keyof WindowEventMap>(type: K, listener: (this: Window, ev: WindowEventMap[K]) => any) => {
  useEffect( () =>  {
    window.addEventListener(type, listener)
    return () => window.removeEventListener(type, listener)
  })
}

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

export const usePrevious = <T>(value: T): T | undefined => {
  const ref = useRef<T>();
  useEffect(() => { ref.current = value });
  return ref.current;
};

/**
 * Wrapper around Reacts useState that only triggers an update if the state changed
 * 
 * @param initial Initial state
 * @returns 
 */
export const useStateNeq = <S>(initial: S | (() => S)): [S, (s:S) => void] => {

  const [state, setState] = useState(initial)
  const prevState = usePrevious(state)

  const updateState = (newState: S) => {
    if (!_.isEqual(newState, prevState))
      setState(newState)
  }

  return [state, updateState]
}