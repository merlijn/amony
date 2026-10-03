import { ReactNode } from "react";
import './ChoiceOption.scss';

type ChoiceOptionProps = {
  type: 'checkbox' | 'radio';
  name?: string;
  value?: string;
  checked: boolean;
  onChange: () => void;
  label?: ReactNode;
};

/**
 * A checkbox or radio with a padded option row whose hover highlight wraps the
 * control (and its label, when present).
 */
const ChoiceOption = ({ type, name, value, checked, onChange, label }: ChoiceOptionProps) => (
  <label className={`choice-option choice-option--${type}`}>
    <input type={type} name={name} value={value} checked={checked} onChange={onChange} />
    { label !== undefined && <span>{ label }</span> }
  </label>
);

export default ChoiceOption;
