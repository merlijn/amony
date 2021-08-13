import Dropdown from "react-bootstrap/Dropdown";
import React, {MouseEventHandler, ReactNode} from "react";
import Button from "react-bootstrap/Button";
import ImgWithAlt from "./ImgWithAlt";

type ToggleProps = {
    children: React.ReactNode;
    onClick: MouseEventHandler<HTMLElement>,
    iconSrc: string,
    iconClassName?: string,
    className: string,
    size?: 'sm' | 'lg';
};
type Props = { children: React.ReactNode; className: string };

const DropDownIconToggle = React.forwardRef<Button, ToggleProps>((props, ref) => (
    <Button
        id="config-button"
        className={props.className}
        onClick={(e) => {
            e.preventDefault();
            props.onClick(e);
        }}
        size={props.size}>
        <ImgWithAlt className={props.iconClassName} src={props.iconSrc} />
        {props.children}
    </Button>
));

const DropDownIconMenu = React.forwardRef<HTMLDivElement, Props>((props, ref) => {
        return (
            <div ref={ref} className={props.className}>
                {props.children}
            </div>
        );
    },
);

const DropDownIcon = (props: { children?: ReactNode, iconSrc: string, iconClassName?: string, buttonClassName?: string, contentClassName?: string }) => {

    return(
        <Dropdown>
            <Dropdown.Toggle as={DropDownIconToggle} iconSrc={props.iconSrc} className={props.buttonClassName}></Dropdown.Toggle>

            <Dropdown.Menu as={DropDownIconMenu} className={props.contentClassName}>
                { props.children }
            </Dropdown.Menu>
        </Dropdown>
    );
}

export default DropDownIcon