-- Mapa de calor: cupons por dia da semana × hora (dimensionar caixas e escala de operadores).
select to_char(data_hora, 'ID-Dy')                    as dia_semana,
       count(*) filter (where extract(hour from data_hora) between 7 and 9)   as "07-09h",
       count(*) filter (where extract(hour from data_hora) between 10 and 12) as "10-12h",
       count(*) filter (where extract(hour from data_hora) between 13 and 15) as "13-15h",
       count(*) filter (where extract(hour from data_hora) between 16 and 18) as "16-18h",
       count(*) filter (where extract(hour from data_hora) between 19 and 21) as "19-21h",
       round(avg(valor_total), 2)                     as ticket_medio
  from erp.cupom
 where not cancelado
 group by 1
 order by 1;
