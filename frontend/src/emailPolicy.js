export const GMAIL_PATTERN = '[^@\\s]+@[gG][mM][aA][iI][lL]\\.[cC][oO][mM]'
export const GMAIL_TITLE = 'Enter a valid @gmail.com address'

export const sameEmail = (left, right) =>
  String(left || '').trim().toLowerCase() === String(right || '').trim().toLowerCase()
