import React, {MutableRefObject, useCallback, useEffect, useRef, useState} from "react";
import _ from "lodash";
import {useLocation, useNavigate} from "react-router-dom";
import {buildUrl, copyParams} from "./Util";

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

export const usePrevious = <T>(value: T): T | undefined => {
  const ref = useRef<T>(undefined);
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

// Custom hook to observe element resize using native ResizeObserver API
export function useResizeObserver<T extends HTMLElement>() {
  const [width, setWidth] = useState<number | undefined>(undefined);
  const [height, setHeight] = useState<number | undefined>(undefined);
  const elementRef = useRef<T>(null);

  const ref = useCallback((element: T | null) => {
    if (element) {
      elementRef.current = element;
    }
  }, []);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;

    const resizeObserver = new ResizeObserver((entries) => {
      if (entries[0]) {
        const { width, height } = entries[0].contentRect;
        setWidth(width);
        setHeight(height);
      }
    });

    resizeObserver.observe(element);

    return () => {
      resizeObserver.disconnect();
    };
  }, []);

  return { ref, width, height };
}