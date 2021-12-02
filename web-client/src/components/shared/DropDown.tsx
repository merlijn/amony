import {ReactNode, useRef, useState} from "react";
import "./DropDown.scss";
import {useListener} from "../../api/ReactUtils";

const DropDown = (props: { children?: ReactNode, label: string, showArrow: boolean, onToggle?: (visible: boolean) => void }) => {

    const [isOpen,setIsOpen] = useState(false)
    const contentRef = useRef<HTMLDivElement>(null)
    const toggleRef = useRef<HTMLDivElement>(null)

    //DropDown toggler
    const toggleDropDown = () => {
        setIsOpen(!isOpen)
        props.onToggle && props.onToggle(!isOpen);
    };

    const handleClickOutside = (event: MouseEvent) => {
        const path = event.composedPath && event.composedPath();

        if (path &&
            contentRef?.current && !path.includes(contentRef.current) &&
            toggleRef?.current && !path.includes(toggleRef.current)) {
            setIsOpen(false)
            props.onToggle && props.onToggle(false);
        }
    };

    useListener('mousedown', handleClickOutside)

    return <div className="my-dropdown-container">
        <div
            className = "my-dropdown-toggle"
            onClick = { toggleDropDown }
            ref = { toggleRef }
        >
            <span className="my-dropdown-label">{props.label}</span>
            { props.showArrow &&  <span className="my-dropdown-arrow">{isOpen ? "\u25B2" : "\u25BC"}</span> }
        </div>
        <div className="my-dropdown-content">
            {isOpen && (
                <div className = "my-dropdown-children" ref = { contentRef }>
                    {props.children}
                </div>
            )}
        </div>
    </div>
}

export default DropDown
